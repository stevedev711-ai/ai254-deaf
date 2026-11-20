"""Train and export the compact glove classifier with signer-held-out evaluation."""
import argparse
import csv
from pathlib import Path

FEATURES = ("thumb", "index", "middle", "ring", "little", "ax", "ay", "az")


def load_sample(path):
    with path.open(newline="") as source:
        rows = list(csv.DictReader(source))
    if len(rows) != 40:
        raise ValueError(f"{path}: expected 40 consecutive frames")
    metadata = {(row["signer"].strip(), row["label"].strip()) for row in rows}
    if len(metadata) != 1:
        raise ValueError(f"{path}: signer and label must be constant")
    signer, label = metadata.pop()
    if not signer or not label:
        raise ValueError(f"{path}: signer and label are required")
    readings = [[int(row[key]) for key in FEATURES] for row in rows]
    if any(not all(0 <= value <= 4095 for value in frame[:5])
           or not all(-32768 <= value <= 32767 for value in frame[5:]) for frame in readings):
        raise ValueError(f"{path}: invalid sensor reading")
    return signer, label, readings


def main():
    import numpy as np
    import tensorflow as tf

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("samples", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--held-out", action="append", required=True, help="Signer IDs reserved for testing")
    args = parser.parse_args()
    samples = [load_sample(path) for path in sorted(args.samples.glob("*.csv"))]
    train = [sample for sample in samples if sample[0] not in args.held_out]
    test = [sample for sample in samples if sample[0] in args.held_out]
    vocabulary = {sample[1] for sample in train}
    if vocabulary != {sample[1] for sample in test} or len(vocabulary) < 2 or "_unknown" not in vocabulary:
        parser.error("Training and held-out signers must each cover every label, including _unknown")
    labels = ["_unknown", *sorted(vocabulary - {"_unknown"})]

    def tensors(split):
        x = np.asarray([sample[2] for sample in split], dtype=np.float32)
        x /= np.asarray([4095] * 5 + [16384] * 3, dtype=np.float32)
        return x, np.asarray([labels.index(sample[1]) for sample in split])

    train_x, train_y = tensors(train)
    test_x, test_y = tensors(test)
    tf.keras.utils.set_random_seed(254)
    model = tf.keras.Sequential([
        tf.keras.Input(shape=(40, 8), batch_size=1),
        tf.keras.layers.Conv1D(24, 5, activation="relu"),
        tf.keras.layers.MaxPooling1D(2),
        tf.keras.layers.Conv1D(16, 3, activation="relu"),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(len(labels), activation="softmax"),
    ])
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy")
    model.fit(train_x, train_y, batch_size=1, epochs=20)
    predictions = model.predict(test_x, batch_size=1, verbose=0)
    selected = predictions.argmax(axis=1)
    selected[predictions.max(axis=1) < 0.85] = 0
    for index, label in enumerate(labels):
        matching = test_y == index
        print(f"{label}: {(selected[matching] == index).sum()}/{matching.sum()}")
    args.output.mkdir(parents=True, exist_ok=True)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    (args.output / "ksl.tflite").write_bytes(converter.convert())
    (args.output / "labels.txt").write_text("\n".join(labels) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
