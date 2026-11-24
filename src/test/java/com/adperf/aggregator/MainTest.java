package com.adperf.aggregator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void parsesBothArgumentForms() {
        Main.Options spaced = Main.Options.parse(new String[]{"--input", "ad_data.csv", "--output", "out/", "--threads", "3"});
        assertThat(spaced.input()).isEqualTo(Path.of("ad_data.csv"));
        assertThat(spaced.outputDir()).isEqualTo(Path.of("out"));
        assertThat(spaced.threads()).isEqualTo(3);

        Main.Options withEquals = Main.Options.parse(new String[]{"--input=ad_data.csv"});
        assertThat(withEquals.outputDir()).isEqualTo(Path.of("results"));
        assertThat(withEquals.threads()).isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> Main.Options.parse(new String[0])).hasMessage("Missing required argument --input");
        assertThatThrownBy(() -> Main.Options.parse(new String[]{"--input"})).hasMessage("Missing value for --input");
        assertThatThrownBy(() -> Main.Options.parse(new String[]{"--input", "a", "--verbose", "x"}))
                .hasMessage("Unknown argument: --verbose");
        assertThatThrownBy(() -> Main.Options.parse(new String[]{"--input", "a", "--threads", "0"}))
                .hasMessage("--threads must be at least 1");
        assertThatThrownBy(() -> Main.Options.parse(new String[]{"--input", "a", "--threads", "abc"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesResultsForValidFile() throws IOException {
        Path input = Files.writeString(dir.resolve("ads.csv"),
                "campaign_id,date,impressions,clicks,spend,conversions\nCMP001,2025-01-01,12000,300,45.50,12\n");

        int exitCode = run(input, dir.resolve("results"));

        assertThat(exitCode).isEqualTo(Main.EXIT_OK);
        assertThat(dir.resolve("results/top10_ctr.csv")).exists();
        assertThat(dir.resolve("results/top10_cpa.csv")).exists();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Processed 1 data rows", "using 2 threads");
    }

    @Test
    void failsOnMissingFile() {
        assertThat(run(dir.resolve("missing.csv"), dir)).isEqualTo(Main.EXIT_INPUT_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("input file not found");
    }

    @Test
    void failsOnInvalidHeaderWithoutWritingResults() throws IOException {
        Path input = Files.writeString(dir.resolve("ads.csv"), "campaign_id,date,clicks\nCMP001,2025-01-01,3\n");

        assertThat(run(input, dir.resolve("results"))).isEqualTo(Main.EXIT_INPUT_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Invalid input file", "exactly 6 columns");
        assertThat(dir.resolve("results")).doesNotExist();
    }

    private int run(Path input, Path outputDir) {
        return Main.run(new Main.Options(input, outputDir, 2),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }
}
