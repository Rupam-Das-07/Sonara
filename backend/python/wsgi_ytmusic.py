# STRICT MONKEY-PATCHING
# MUST BE EXECUTED BEFORE ANY OTHER IMPORTS
import gevent.monkey
gevent.monkey.patch_all()

# Import the Flask app object from ytmusic_service
from ytmusic_service import app

if __name__ == "__main__":
    app.run()

