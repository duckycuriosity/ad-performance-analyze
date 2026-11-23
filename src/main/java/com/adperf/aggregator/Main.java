package com.adperf.aggregator;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point. With {@code --input} it analyses a file and exits (CLI); without it, it starts a small web
 * page for uploading a file.
 */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;
    static final int EXIT_INPUT_ERROR = 3;
    static final int EXIT_IO_ERROR = 4;

    private static final String USAGE = """
            Usage:
              java -jar ad-aggregator.jar --input <file.csv> [--output <dir>]   analyse a file
              java -jar ad-aggregator.jar [--port <port>]                       start the upload page""";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.input() == null) {
            try {
                WebServer.start(options.port());
            } catch (BindException e) {
                System.err.printf("Error: port %d is already in use. Choose another one, e.g. --port %d%n",
                        options.port(), options.port() + 1);
                System.exit(EXIT_IO_ERROR);
            }
            System.out.println("Upload page running at http://localhost:" + options.port());
            return;
        }
        System.exit(runCli(options.input(), options.outputDir(), System.out, System.err));
    }

    static int runCli(Path input, Path outputDir, PrintStream out, PrintStream err) {
        if (!Files.isRegularFile(input)) {
            err.println("Error: input file not found: " + input.toAbsolutePath());
            return EXIT_INPUT_ERROR;
        }
        try (InputStream stream = Files.newInputStream(input)) {
            Report report = Aggregator.analyze(stream);
            ResultCsv.writeFiles(report, outputDir);
            printSummary(report, outputDir, out);
            return EXIT_OK;
        } catch (InvalidInputException e) {
            err.println("Invalid input file: " + e.getMessage());
            return EXIT_INPUT_ERROR;
        } catch (IOException e) {
            err.println("I/O error: " + e.getMessage());
            return EXIT_IO_ERROR;
        }
    }

    private static void printSummary(Report report, Path outputDir, PrintStream out) {
        out.printf("Processed %,d data rows in %.2f s%n", report.dataRows(), report.elapsed().toMillis() / 1000.0);
        out.printf("  valid rows:    %,d%n", report.validRows());
        out.printf("  skipped rows:  %,d%n", report.skippedRows());
        report.skippedByReason().forEach((reason, count) ->
                out.printf("    %-30s %,d%n", reason.description + ":", count));
        report.skippedSamples().forEach(row ->
                out.printf("    line %d (%s): %s%n", row.lineNumber(), row.reason().description, row.content()));
        out.printf("  campaigns:     %,d%n", report.campaignCount());
        out.println("Results written to:");
        out.println("  " + outputDir.resolve(ResultCsv.TOP_CTR_FILE).toAbsolutePath());
        out.println("  " + outputDir.resolve(ResultCsv.TOP_CPA_FILE).toAbsolutePath());
    }

    /** Command-line options. Accepts both {@code --name value} and {@code --name=value}. */
    record Options(Path input, Path outputDir, int port) {

        static final Path DEFAULT_OUTPUT_DIR = Path.of("results");
        static final int DEFAULT_PORT = 8080;

        static Options parse(String[] args) {
            Path input = null;
            Path outputDir = DEFAULT_OUTPUT_DIR;
            int port = DEFAULT_PORT;
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
                    case "--port" -> port = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("Unknown argument: " + name);
                }
            }
            return new Options(input, outputDir, port);
        }
    }
}
