package dev.vexelray.gui.formatbench;

import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the comparison and writes it: sizes on disk, times to load and to draw (JMH), and error against the reference
 * (CPU contenders and the SDG drawn on the GPU by a headless window). The document is written by this and nothing
 * else. A second argument, a substring of case keys, runs some cases only and says so.
 */
public final class Publish {

    private Publish() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "comparison.md");
        String only = args.length > 1 ? args[1] : null;
        List<String> keys = new ArrayList<>();
        for (String k : Cases.all().keySet()) {
            if (only == null || k.contains(only)) {
                keys.add(k);
            }
        }
        Options opt = new OptionsBuilder().include(FormatBench.class.getName())
                .param("key", keys.toArray(new String[0])).build();
        Map<String, Double> time = new LinkedHashMap<>();
        Map<String, Double> err = new LinkedHashMap<>();
        for (RunResult r : new Runner(opt).run()) {
            Result<?> p = r.getPrimaryResult();
            time.put(r.getParams().getParam("key"), p.getScore());
            err.put(r.getParams().getParam("key"), p.getScoreError());
        }

        // quality: every CPU contender and the GPU, every art, every size
        Path shots = Files.createTempDirectory("format-bench");
        float bg = 0.5f;
        Map<String, Quality.Error> quality = new LinkedHashMap<>();
        for (String name : Corpus.names()) {
            Corpus.Art art = Corpus.get(name);
            for (int side : Corpus.SIZES) {
                byte[] truth = Quality.reference(art, side);
                for (Contender c : Contender.all()) {
                    quality.put(name + "|" + c.name() + "|" + side, Quality.compare(truth, c.draw(art, side)));
                }
                quality.put(name + "|SDG_GPU|" + side,
                        Quality.compare(Quality.over(truth, bg), Quality.gpu(art, side, bg, shots)));
            }
        }
        // the GPU's draw time, by Vulkan timestamps: the SDG, and the shipped picture as a texture
        Map<String, Double> gpu = new LinkedHashMap<>();
        for (String name : Corpus.names()) {
            for (int side : Corpus.SIZES) {
                for (GpuTime.Kind k : GpuTime.Kind.values()) {
                    gpu.put(name + "|" + k.name() + "|" + side, GpuTime.millis(Corpus.get(name), side, k));
                }
            }
        }
        Files.writeString(out, render(time, err, quality, gpu, only), StandardCharsets.UTF_8);
        System.out.println("wrote " + out.toAbsolutePath());
    }

    static String render(Map<String, Double> time, Map<String, Double> err, Map<String, Quality.Error> quality,
                         Map<String, Double> gpu, String only) {
        StringBuilder s = new StringBuilder();
        s.append("# SDG, SVG and rasters compared\n\n");
        s.append("**Written by vexelray-gui-format-bench's `Publish`, not by hand.** The same art in each form, at the\n");
        s.append("same sizes: what it costs on disk, to load and to draw, and how far what it draws is from the truth.\n");
        s.append("Run it again with:\n\n```\nmvn -pl vexelray-gui-format-bench -am install -DskipTests\n");
        s.append("mvn -pl vexelray-gui-format-bench exec:exec\n```\n\n");
        if (only != null) {
            s.append("**A partial run:** only the timed cases whose key contains `").append(only).append("`.\n\n");
        }
        s.append("## The run\n\n| | |\n| --- | --- |\n");
        s.append("| Date | ").append(LocalDate.now()).append(" |\n");
        s.append("| CPU | ").append(cpu()).append(" |\n");
        s.append("| Cores the JVM sees | ").append(Runtime.getRuntime().availableProcessors()).append(" |\n");
        s.append("| OS | ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(" |\n");
        s.append("| JDK | ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.runtime.version")).append(" |\n");
        s.append("| JMH | average time per operation, 1 fork, 2 warm-up and 3 measured iterations of 1 s, one thread |\n\n");

        s.append("## Method\n\n");
        s.append("* **The art** is six of the suite's own drawings, square, as SVG:\n\n");
        for (String name : Corpus.names()) {
            s.append("  * `").append(name).append("`: ").append(Corpus.ART.get(name)).append('\n');
        }
        s.append("\n* **The forms.** The SVG itself. The SDG made from it, ").append(Corpus.TEXELS)
                .append(" texels across, as the icon plugin makes a mark, compiled (`.sdgb`) and as XML (`.sdg`).\n");
        s.append("  A PNG of it at 256 and at 1024 pixels, drawn by imagelib, as an application ships a picture.\n");
        s.append("* **The contenders**, each drawing the art at 64, 256 and 1024 pixels square on the CPU:\n\n");
        for (Contender c : Contender.all()) {
            s.append("  * **").append(c.label).append("**: ").append(c.how).append('\n');
        }
        s.append("  * **SDG, GPU**: the SDG drawn by a node in a headless window, the stack's own renderer: its error from\n");
        s.append("    a capture (`GuiApp.capture`), its time from Vulkan timestamps (`GuiApp.gpuMillis`): ")
                .append(GpuTime.COPIES).append(" copies in a frame,\n    less an empty frame, over ")
                .append(GpuTime.COPIES).append(", the median of ").append(GpuTime.FRAMES).append(" frames.\n");
        s.append("  * **PNG 1024, GPU**: the 1024 px PNG drawn the way a picture is, a still feed with its mip chain,\n");
        s.append("    sampled at the size: the GPU's own way to show a raster, the one to set SDG against there.\n\n");
        s.append("* **The truth** is the SVG drawn by imagelib at four times the size and averaged down four by four.\n");
        s.append("  Error is in premultiplied colour, every channel of every pixel, in steps of 1/255: the mean, the\n");
        s.append("  worst, and the share of pixels with a channel more than ").append(Quality.VISIBLE)
                .append(" off. The GPU's is over a grey page, its truth laid over the same grey.\n");
        s.append("* **Read with care:** imagelib is also the truth's renderer, so its own row measures antialiasing\n");
        s.append("  alone; an SDG's error includes what making it lost (its texels); a PNG is exact only at its size.\n\n");

        s.append("## Size on disk\n\nBytes, as each form is shipped (not compressed further).\n\n| Art |");
        for (String f : Cases.FORMS) {
            s.append(' ').append(f).append(" |");
        }
        s.append("\n| --- |");
        s.append(" ---: |".repeat(Cases.FORMS.length));
        s.append('\n');
        for (String name : Corpus.names()) {
            s.append("| `").append(name).append("` |");
            for (String f : Cases.FORMS) {
                s.append(' ').append(bytes(Cases.bytes(Corpus.get(name), f))).append(" |");
            }
            s.append('\n');
        }

        s.append("\n## Load\n\nFrom the bytes to what draws: an SVG parsed (vexelray-fields), an SDG read with its pages\n");
        s.append("decoded, a PNG decoded (imagelib). imagelib's SVG path parses on every draw, so it has no load of its own.\n\n| Art |");
        for (String f : Cases.FORMS) {
            s.append(' ').append(f).append(" |");
        }
        s.append("\n| --- |").append(" ---: |".repeat(Cases.FORMS.length)).append('\n');
        for (String name : Corpus.names()) {
            s.append("| `").append(name).append("` |");
            for (String f : Cases.FORMS) {
                s.append(' ').append(time(time, err, "load|" + name + "|" + f)).append(" |");
            }
            s.append('\n');
        }

        for (int side : Corpus.SIZES) {
            s.append("\n## At ").append(side).append(" pixels\n\n### Draw time\n\n| Art |");
            for (Contender c : Contender.all()) {
                s.append(' ').append(c.label).append(" |");
            }
            s.append("\n| --- |").append(" ---: |".repeat(Contender.all().size())).append('\n');
            for (String name : Corpus.names()) {
                s.append("| `").append(name).append("` |");
                for (Contender c : Contender.all()) {
                    s.append(' ').append(time(time, err, "draw|" + name + "|" + c.name() + "|" + side)).append(" |");
                }
                s.append('\n');
            }
            s.append("\n### GPU draw time\n\nOne drawing at the size, by Vulkan timestamps (see *Method*).\n\n| Art |");
            for (GpuTime.Kind k : GpuTime.Kind.values()) {
                s.append(' ').append(k.label).append(" |");
            }
            s.append("\n| --- |").append(" ---: |".repeat(GpuTime.Kind.values().length)).append('\n');
            for (String name : Corpus.names()) {
                s.append("| `").append(name).append("` |");
                for (GpuTime.Kind k : GpuTime.Kind.values()) {
                    Double ms = gpu.get(name + "|" + k.name() + "|" + side);
                    s.append(' ').append(ms == null ? "—" : ms >= 1 ? String.format(Locale.ROOT, "%.2f ms", ms)
                            : String.format(Locale.ROOT, "%.1f µs", ms * 1000)).append(" |");
                }
                s.append('\n');
            }
            s.append("\n### Error against the truth\n\nMean / worst / share of pixels past ").append(Quality.VISIBLE)
                    .append(".\n\n| Art |");
            for (Contender c : Contender.all()) {
                s.append(' ').append(c.label).append(" |");
            }
            s.append(" SDG, GPU |\n| --- |").append(" ---: |".repeat(Contender.all().size() + 1)).append('\n');
            for (String name : Corpus.names()) {
                s.append("| `").append(name).append("` |");
                for (Contender c : Contender.all()) {
                    s.append(' ').append(error(quality.get(name + "|" + c.name() + "|" + side))).append(" |");
                }
                s.append(' ').append(error(quality.get(name + "|SDG_GPU|" + side))).append(" |\n");
            }
        }
        return s.toString();
    }

    private static String time(Map<String, Double> time, Map<String, Double> err, String key) {
        Double t = time.get(key);
        if (t == null) {
            return "—";
        }
        double e = err.get(key);
        return t >= 1000 ? String.format(Locale.ROOT, "%.2f ± %.2f ms", t / 1000, e / 1000)
                : String.format(Locale.ROOT, "%.1f ± %.1f µs", t, e);
    }

    private static String error(Quality.Error e) {
        return e == null ? "—" : String.format(Locale.ROOT, "%.2f / %d / %.1f%%", e.mean(), e.worst(), e.visible() * 100);
    }

    private static String bytes(int b) {
        return b >= 1024 ? String.format(Locale.ROOT, "%.1f KiB", b / 1024.0) : b + " B";
    }

    private static String cpu() {
        try {
            if (Files.exists(Path.of("/proc/cpuinfo"))) {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).strip();
                    }
                }
            }
            // the CPU's own name where PowerShell answers; where there is none, starting it throws and the environment speaks
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance Win32_Processor | Select-Object -First 1).Name").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                p.waitFor();
                if (line != null && !line.isBlank()) {
                    return line.strip();
                }
            }
        } catch (Exception e) {
            // the environment's word, below
        }
        String id = System.getenv("PROCESSOR_IDENTIFIER");
        return id != null ? id : "unknown";
    }
}
