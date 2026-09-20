# STRICT MONKEY-PATCHING
# MUST BE EXECUTED BEFORE ANY OTHER IMPORTS
import gevent.monkey
gevent.monkey.patch_all()

# Import the Flask app object from the youtube_audio_api script
from youtube_audio_api import app

if __name__ == "__main__":
    app.run()
