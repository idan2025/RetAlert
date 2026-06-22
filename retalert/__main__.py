"""Entry point: ``python -m retalert``."""
import sys

from .cli import main

if __name__ == "__main__":
    sys.exit(main())