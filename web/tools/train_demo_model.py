"""Train the browser demo's classifier on simulated glove windows.

Uses the same network as training/train.py and the same simulator as the
web page (web/signs.json), then writes the weights to web/model.json for
the in-browser forward pass. The result demonstrates the pipeline only:
it has never seen real KSL data.
"""
import json, math, random, sys
from pathlib import Path

WEB = Path(__file__).resolve().parent.parent
SPEC = json.loads((WEB / "signs.json").read_text())["signs"]


def window(label, rng):
    bias = rng.uniform(-0.08, 0.08)
    if label == "_unknown":
        if rng.random() < 0.5:   # near match: a real sign shape, distorted and moving wrongly
            base = SPEC[rng.choice(list(SPEC))]["bends"]
            bends = [min(1, max(0, b + rng.uniform(-0.45, 0.45))) for b in base]
            motion = [rng.uniform(0, 2500) for _ in range(3)]
        else:                    # resting hand
            bends, motion = [rng.uniform(0, 0.3)] * 5, [0, 0, 0]
    else:
        bends, motion = SPEC[label]["bends"], SPEC[label]["motion"]
    phase = rng.uniform(0, 1)
    frames = []
    for t in range(40):
        w = math.sin(2 * math.pi * (t / 20 + phase))
        flex = [min(4095, max(0, round(4095 * (1 - 0.7 * min(1, max(0, b + bias))) + rng.gauss(0, 60)))) for b in bends]
        acc = [round(a * w + rng.gauss(0, 300)) for a in motion]
        acc[2] += 16384
        frames.append(flex + acc)
    return frames


def main():
    import numpy as np
    import tensorflow as tf

    labels = ["_unknown", *sorted(SPEC)]
    rng = random.Random(254)

    def dataset(n):
        x, y = [], []
        for index, label in enumerate(labels):
            for _ in range(n * (2 if index == 0 else 1)):
                x.append(window(label, rng)); y.append(index)
        x = np.asarray(x, np.float32) / np.asarray([4095] * 5 + [16384] * 3, np.float32)
        return x, np.asarray(y)

    train_x, train_y = dataset(60)
    test_x, test_y = dataset(20)
    tf.keras.utils.set_random_seed(254)
    model = tf.keras.Sequential([
        tf.keras.Input(shape=(40, 8)),
        tf.keras.layers.Conv1D(24, 5, activation="relu"),
        tf.keras.layers.MaxPooling1D(2),
        tf.keras.layers.Conv1D(16, 3, activation="relu"),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(len(labels), activation="softmax"),
    ])
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy")
    model.fit(train_x, train_y, batch_size=8, epochs=100, verbose=0, shuffle=True)
    p = model.predict(test_x, verbose=0)
    pick = p.argmax(1); pick[p.max(1) < 0.85] = 0
    for i, label in enumerate(labels):
        m = test_y == i
        print(f"{label}: {(pick[m] == i).sum()}/{m.sum()}")
    conv1, _, conv2, _, dense = model.layers
    out = {"labels": labels, "threshold": 0.85,
           "conv1": [w.round(6).tolist() for w in conv1.get_weights()],
           "conv2": [w.round(6).tolist() for w in conv2.get_weights()],
           "dense": [w.round(6).tolist() for w in dense.get_weights()]}
    (WEB / "model.json").write_text(json.dumps(out, separators=(",", ":")))
    # reference outputs so the JavaScript forward pass can be checked against Keras
    ref = {"input": test_x[[0, 50, 110]].tolist(), "output": model.predict(test_x[[0, 50, 110]], verbose=0).tolist()}
    Path(sys.argv[1] if len(sys.argv) > 1 else "/dev/null").write_text(json.dumps(ref))


if __name__ == "__main__":
    main()
