package dev.vexelray.gui.formatbench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One {@link Cases} case a run: its art loaded once, outside the timing, then the case once an operation. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
public class FormatBench {

    @Param("load|badge|SVG")
    public String key;

    private Supplier<Object> run;

    @Setup(Level.Trial)
    public void setUp() {
        run = Cases.all().get(key);
        if (run == null) {
            throw new IllegalArgumentException("no case " + key);
        }
        Corpus.get(key.split("\\|")[1]);   // made before the clock starts
    }

    @Benchmark
    public Object run() {
        return run.get();
    }
}
