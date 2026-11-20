package ke.ai254;

import java.io.File;
import java.util.List;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

final class Signs implements AutoCloseable {
    static final int FRAMES = 40;
    static final int FEATURES = 8;
    private final Interpreter interpreter;
    private final List<String> labels;
    private final float[][][] window = new float[1][FRAMES][FEATURES];
    private final float[][] probabilities;
    private int frames;
    private int lastSequence = -1;

    Signs(File model, List<String> labels) {
        if (labels.size() < 2 || !labels.get(0).equals("_unknown") || labels.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Labels must start with _unknown and include a classroom phrase");
        this.labels = labels;
        probabilities = new float[1][labels.size()];
        interpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(2));
        if (!java.util.Arrays.equals(interpreter.getInputTensor(0).shape(), new int[]{1, FRAMES, FEATURES})
                || !java.util.Arrays.equals(interpreter.getOutputTensor(0).shape(), new int[]{1, labels.size()})
                || interpreter.getInputTensor(0).dataType() != DataType.FLOAT32
                || interpreter.getOutputTensor(0).dataType() != DataType.FLOAT32) {
            interpreter.close();
            throw new IllegalArgumentException("KSL model input or output does not match the glove protocol");
        }
    }

    void reset() { frames = 0; lastSequence = -1; }

    String add(short[] readings) {
        if (frames == FRAMES) return null;
        int sequence = Short.toUnsignedInt(readings[0]);
        if (lastSequence >= 0 && sequence != ((lastSequence + 1) & 65535)) frames = 0;
        lastSequence = sequence;
        for (int i = 0; i < FEATURES; i++) {
            window[0][frames][i] = readings[i + 1] / (i < 5 ? 4095.0f : 16384.0f);
        }
        if (++frames != FRAMES) return null;
        interpreter.run(window, probabilities);
        int best = 0;
        for (int i = 1; i < labels.size(); i++) {
            if (probabilities[0][i] > probabilities[0][best]) best = i;
        }
        return best == 0 || !(probabilities[0][best] >= 0.85f) ? "" : labels.get(best);
    }

    @Override public void close() { interpreter.close(); }
}
