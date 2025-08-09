from fastapi import FastAPI, UploadFile, File, Response
from fastapi.middleware.cors import CORSMiddleware
from TTS.api import TTS
import whisper
from litellm import completion
import tempfile
import os
import yaml
from dotenv import load_dotenv
from pydub import AudioSegment
import base64
from fastapi.responses import JSONResponse

# Load environment variables and configuration
load_dotenv()
with open('config.yaml', 'r') as file:
    config = yaml.safe_load(file)

# Initialize AI models
stt = whisper.load_model(config["whisper"]["model"])
tts = TTS(model_name=config["tts"]["model"], progress_bar=False)
llm_config = config["llm"]

# Set up FastAPI app with CORS
app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.post("/process_audio")
async def process_audio(file: UploadFile = File(...)):
    # Save uploaded audio to a temporary file
    with tempfile.NamedTemporaryFile(suffix=".m4a", delete=False) as temp_audio:
        audio_data = await file.read()
        temp_audio.write(audio_data)
        audio_path = temp_audio.name
    print(f"Received audio file: {audio_path}, size: {len(audio_data)} bytes")

    # Convert audio to WAV using pydub
    try:
        audio = AudioSegment.from_file(audio_path, format="m4a")
        print(f"Audio file details: channels={audio.channels}, sample_rate={audio.frame_rate}, duration={len(audio)/1000:.2f}s")
        wav_path = audio_path.replace(".m4a", ".wav")
        audio = audio.set_channels(1).set_frame_rate(16000)  # Ensure mono and 16kHz
        audio.export(wav_path, format="wav")
        print(f"Saved audio as WAV: {wav_path}, size: {os.path.getsize(wav_path)} bytes")
    except Exception as e:
        print(f"Audio preprocessing error: {str(e)}")
        os.remove(audio_path)
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp_synth:
            tts.tts_to_file(
                text="Audio processing failed. Please try again.",
                speaker=config["tts"]["speakerId"] if tts.is_multi_speaker else None,
                file_path=temp_synth.name
            )
            with open(temp_synth.name, "rb") as f:
                audio_data = f.read()
            os.remove(temp_synth.name)
        return JSONResponse(content={
            "text": "Audio processing failed. Please try again.",
            "audio": base64.b64encode(audio_data).decode('utf-8')
        })

    # Transcribe audio using Whisper
    try:
        transcription = stt.transcribe(wav_path, language="en")
        text = transcription["text"]
        language = transcription["language"]
        print(f"Transcribed text: '{text}', Language: {language}")
    except Exception as e:
        print(f"Transcription error: {str(e)}")
        text = "Transcription failed. Please try again."
        language = "en"

    # Clean up audio files
    os.remove(audio_path)
    if os.path.exists(wav_path):
        os.remove(wav_path)

    # Prepare LLM request
    messages = [{"role": "system", "content": llm_config["system"]}]
    if not text.strip():
        text = "No input detected. Please ask a question."
    messages.append({"role": "user", "content": text})
    print(f"LLM input messages: {messages}")

    # Get LLM response
    try:
        response = completion(
            model=llm_config["model"],
            messages=messages,
            api_base=llm_config["url"],
            stream=False
        )
        llm_response = response["choices"][0]["message"]["content"]
    except Exception as e:
        llm_response = f"Error processing LLM: {str(e)}"
    print(f"LLM response: '{llm_response}'")

    # Synthesize response to audio using TTS
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp_synth:
        try:
            tts.tts_to_file(
                text=llm_response,
                speaker=config["tts"]["speakerId"] if tts.is_multi_speaker else None,
                language=language if tts.is_multi_lingual and language in tts.languages else None,
                file_path=temp_synth.name
            )
        except Exception as e:
            print(f"TTS error: {str(e)}")
            tts.tts_to_file(
                text="Sorry, I couldn't generate a response. Please try again.",
                speaker=config["tts"]["speakerId"] if tts.is_multi_speaker else None,
                file_path=temp_synth.name
            )
        synth_path = temp_synth.name

    # Read and encode synthesized audio as base64
    with open(synth_path, "rb") as f:
        audio_data = f.read()
    audio_base64 = base64.b64encode(audio_data).decode('utf-8')
    print(f"Synthesized audio size: {len(audio_data)} bytes")
    os.remove(synth_path)

    # Return JSON with text and audio
    return JSONResponse(content={
        "text": llm_response,
        "audio": audio_base64
    })
