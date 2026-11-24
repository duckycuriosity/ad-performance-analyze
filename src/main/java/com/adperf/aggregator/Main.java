package com.adperf.aggregator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-line entry point: analyses one CSV file and writes the two result files. */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_INPUT_ERROR = 3;
    static final int EXIT_IO_ERROR = 4;

    private static final String USAGE = """
            Usage: ad-aggregator.exe --input <file.csv> [--output <dir>] [--threads <n>]
               or: java -jar ad-aggregator.jar --input <file.csv> [--output <dir>] [--threads <n>]""";

    private Main() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(EXIT_USAGE);
            return;
        }
        System.exit(run(options, System.out, System.err));
    }

    static int run(Options options, PrintStream out, PrintStream err) {
        if (!Files.isRegularFile(options.input())) {
            err.println("Error: input file not found: " + options.input().toAbsolutePath());
            return EXIT_INPUT_ERROR;
        }
        try {
            Report report = Aggregator.analyze(options.input(), options.threads());
            ResultCsv.writeFiles(report, options.outputDir());
            printSummary(report, options, out);
            return EXIT_OK;
        } catch (InvalidInputException e) {
            err.println("Invalid input file: " + e.getMessage());
            return EXIT_INPUT_ERROR;
        } catch (IOException e) {
            err.println("I/O error: " + e.getMessage());
            return EXIT_IO_ERROR;
        }
    }

    private static void printSummary(Report report, Options options, PrintStream out) {
        out.printf("Processed %,d data rows in %.2f s using %d threads%n",
                report.dataRows(), report.elapsed().toMillis() / 1000.0, options.threads());
        out.printf("  valid rows:    %,d%n", report.validRows());
        out.printf("  skipped rows:  %,d%n", report.skippedRows());
        report.skippedByReason().forEach((reason, count) ->
                out.printf("    %-30s %,d%n", reason.description + ":", count));
        report.skippedSamples().forEach(row ->
                out.printf("    line %d (%s): %s%n", row.lineNumber(), row.reason().description, row.content()));
        out.printf("  campaigns:     %,d%n", report.campaignCount());
        out.println("Results written to:");
        out.println("  " + options.outputDir().resolve(ResultCsv.TOP_CTR_FILE).toAbsolutePath());
        out.println("  " + options.outputDir().resolve(ResultCsv.TOP_CPA_FILE).toAbsolutePath());
    }

    /**
     * Command-line options. Accepts both {@code --name value} and {@code --name=value}.
     * The thread count defaults to the number of available processors.
     */
    record Options(Path input, Path outputDir, int threads) {

        static final Path DEFAULT_OUTPUT_DIR = Path.of("results");

        static Options parse(String[] args) {
            Path input = null;
            Path outputDir = DEFAULT_OUTPUT_DIR;
            int threads = Runtime.getRuntime().availableProcessors();
            for (int i = 0; i < args.length; i++) {
                int equals = args[i].indexOf('=');
                String name = equals < 0 ? args[i] : args[i].substring(0, equals);
                String value;
                if (equals >= 0) {
                    value = args[i].substring(equals + 1);
                } else if (i + 1 < args.length) {
                    value = args[++i];
                } else {
                    value = "";
                }
                if (value.isBlank()) {
                    throw new IllegalArgumentException("Missing value for " + name);
                }
                switch (name) {
                    case "--input" -> input = Path.of(value);
                    case "--output" -> outputDir = Path.of(value);
                    case "--threads" -> threads = parseThreads(value);
                    default -> throw new IllegalArgumentException("Unknown argument: " + name);
                }
            }
            if (input == null) {
                throw new IllegalArgumentException("Missing required argument --input");
            }
            return new Options(input, outputDir, threads);
        }

        private static int parseThreads(String value) {
            int threads = Integer.parseInt(value);
            if (threads < 1) {
                throw new IllegalArgumentException("--threads must be at least 1");
            }
            return threads;
        }
    }
}
