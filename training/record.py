"""Record one labelled two-second window from the wired glove."""
import argparse
import csv
import time
from pathlib import Path


def main():
    import serial

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port")
    parser.add_argument("signer", help="Pseudonymous signer ID")
    parser.add_argument("label", help="Reviewed spoken label, or _unknown")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not args.signer.strip() or not args.label.strip():
        parser.error("Signer ID and label are required")
    with serial.Serial(args.port, 115200, timeout=0.25) as glove:
        time.sleep(2)
        input("Agree on the sign and press Enter when ready. ")
        glove.reset_input_buffer()
        print("Sign now", flush=True)
        deadline = time.monotonic() + 4
        samples = []
        while len(samples) < 40 and time.monotonic() < deadline:
            try:
                values = list(map(int, glove.readline().split(b",")))
            except ValueError:
                continue
            if len(values) == 8:
                samples.append(values)
        if len(samples) != 40:
            raise SystemExit("Incomplete sample. No file written.")
    with args.output.open("x", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(["signer", "label", "thumb", "index", "middle", "ring", "little", "ax", "ay", "az"])
        writer.writerows([args.signer, args.label, *values] for values in samples)


if __name__ == "__main__":
    main()
