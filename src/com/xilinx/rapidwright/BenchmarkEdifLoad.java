package com.xilinx.rapidwright;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFParser;
import com.xilinx.rapidwright.edif.ParallelEDIFParser;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Installer;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.NullOutputStream;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.function.ThrowingConsumer;

public class BenchmarkEdifLoad {

    private static String checksum(ThrowingConsumer<OutputStream, IOException> consumer) {
        try {
            final MessageDigest sha1 = MessageDigest.getInstance("SHA1");
            try (DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), sha1)) {
                consumer.accept(dos);
                return Installer.bytesToString(sha1.digest());
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }

    }

    private static abstract class ParserBenchmark<T> {
        protected abstract int getAllThreads(T t);

        protected abstract int getSuccessfulThreads(T t);

        protected abstract T makeParser(Path p) throws IOException;

        protected abstract EDIFNetlist parse(T t) throws IOException;

        private EDIFNetlist netlist;

        public void freeNetlist() {
            netlist = null;
        }

        private void warmUpCache(Path p) {
            final String md5 = Installer.calculateMD5OfFile(p);
            System.out.println("source edif has md5: "+md5);
        }


        private Stats benchmark(Path file) throws IOException {
            warmUpCache(file);
            System.gc();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.gc();
            final long usageBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            final long start = System.nanoTime();
            T parser = makeParser(file);

            this.netlist = parse(parser);
            final long end = System.nanoTime();
            System.gc();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.gc();
            final long usageDuring = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            int threads = getAllThreads(parser);
            int successfulThreads = getSuccessfulThreads(parser);
            //Free reference
            parser = null;
            System.gc();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.gc();
            final long usageAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memEdif = usageAfter - usageBefore;
            long memParser = usageDuring - usageAfter;
            return new Stats((end - start), memParser, memEdif, threads, successfulThreads, checksum(netlist::exportEDIF), checksum(netlist::writeBinaryEDIF));
        }

        public Stats benchmark(Path origFile, boolean isFullRun) throws IOException {
            if (isFullRun) {
                Path p = copyCloser(origFile);
                try {
                    return benchmark(p);
                } finally {
                    Files.delete(p);
                }
            } else {
                return benchmark(origFile);
            }
        }

        private Path copyCloser(Path origFile) throws IOException {
            final List<String> possibilities = Arrays.asList("/scratch", "/tmp");
            for (String possibility : possibilities) {
                try {
                    Path dir = Paths.get(possibility);
                    if (Files.exists(dir)) {
                        String username = System.getenv("USER");
                        if (username == null || username.isEmpty()) {
                            throw new RuntimeException("did not find username");
                        }
                        final Path myDir = dir.resolve(username);
                        if (!Files.isDirectory(myDir)) {
                            try {
                                Files.createDirectory(myDir, PosixFilePermissions.asFileAttribute(new HashSet<>(Arrays.asList(
                                        PosixFilePermission.OWNER_READ,
                                        PosixFilePermission.OWNER_WRITE,
                                        PosixFilePermission.OWNER_EXECUTE
                                ))));
                            } catch (IOException e) {
                                //If it exists now, we assume another process was faster at creating it.
                                //Swallow the exception in that case
                                if (!Files.isDirectory(myDir)) {
                                    throw e;
                                }
                            }
                        }
                        Path target = Files.createTempFile(myDir, "rapidWright_benchmark_edif_copy", ".edf");
                        System.out.println("copied input file to " + target);
                        Files.copy(origFile, target, StandardCopyOption.REPLACE_EXISTING);
                        return target;
                    }
                } catch (IOException e) {
                    System.out.println("Possibility "+possibility+" failed:"+ e);
                    e.printStackTrace(System.out);
                    //Try next..
                }
            }
            throw new RuntimeException("Don't know a good location to copy our file to!");
        }
    }

    static class BenchmarkSerialParser extends ParserBenchmark<EDIFParser> {

        @Override
        protected int getAllThreads(EDIFParser edifParser) {
            return 1;
        }

        @Override
        protected int getSuccessfulThreads(EDIFParser edifParser) {
            return 1;
        }

        @Override
        protected EDIFParser makeParser(Path p) throws IOException {
            return new EDIFParser(p);
        }

        @Override
        protected EDIFNetlist parse(EDIFParser edifParser) {
            return edifParser.parseEDIFNetlist();
        }
    }

    static class BenchmarkParallelParser extends ParserBenchmark<ParallelEDIFParser> {


        public BenchmarkParallelParser() {
        }

        @Override
        protected int getAllThreads(ParallelEDIFParser parallelEDIFParser) {
            return parallelEDIFParser.getNumberOfThreads();
        }

        @Override
        protected int getSuccessfulThreads(ParallelEDIFParser parallelEDIFParser) {
            return parallelEDIFParser.getSuccesfulThreads();
        }

        @Override
        protected ParallelEDIFParser makeParser(Path p) throws IOException {
            return new ParallelEDIFParser(p);
        }

        @Override
        protected EDIFNetlist parse(ParallelEDIFParser parallelEDIFParser) throws IOException {
            CodePerfTracker t = new CodePerfTracker("Parallel EDIF Load");
            final EDIFNetlist edifNetlist = parallelEDIFParser.parseEDIFNetlist(t);
            t.printSummary();
            parallelEDIFParser.printNetlistStats(edifNetlist);
            return edifNetlist;
        }
    }

    private static class Stats {
        public final long runtime;
        public final long parserMem;
        public final long edifMem;
        public final int threads;
        public final int successfulThreads;
        private final String checksumEdif;
        private final String checksumBinaryEdif;

        private Stats(long runtime, long parserMem, long edifMem, int threads, int successfulThreads, String checksumEdif, String checksumBinaryEdif) {
            this.runtime = runtime;
            this.parserMem = parserMem;
            this.edifMem = edifMem;
            this.threads = threads;
            this.successfulThreads = successfulThreads;
            this.checksumEdif = checksumEdif;
            this.checksumBinaryEdif = checksumBinaryEdif;
        }

        private static String requireStart(String line, String start) {
            if (!line.startsWith(start)) {
                throw new RuntimeException(line+" should start with "+start+" but does not");
            }
            return line.substring(start.length());
        }

        public static Stats read(Path path) {
            try {
                final List<String> lines = Files.readAllLines(path);
                final long runtime = Long.parseLong(requireStart(lines.get(0), "runtime="));
                final long parserMem = Long.parseLong(requireStart(lines.get(1), "parserMem="));
                final long edifMem = Long.parseLong(requireStart(lines.get(2), "edifMem="));
                final int threads = Integer.parseInt(requireStart(lines.get(3), "threads="));
                final int successfulThreads = Integer.parseInt(requireStart(lines.get(4), "successfulThreads="));
                final String checksumEdif = requireStart(lines.get(5), "checksumEdif=");
                final String checksumBinaryEdif = requireStart(lines.get(6), "checksumBinaryEdif=");

                return new Stats(runtime, parserMem, edifMem, threads, successfulThreads, checksumEdif, checksumBinaryEdif);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private List<String> dump() {
            return Arrays.asList(
                    "runtime=" + runtime,
                    "parserMem=" + parserMem,
                    "edifMem=" + edifMem,
                    "threads=" + threads,
                    "successfulThreads=" + successfulThreads,
                    "checksumEdif="+checksumEdif,
                    "checksumBinaryEdif="+checksumBinaryEdif
            );
        }

        public void print() {
            for (String s : dump()) {
                System.out.println(s);
            }
        }

        public void save(Path p) throws IOException {
            Files.write(p, dump());
        }
    }

    private static final Map<String, ParserBenchmark<?>> configs = makeConfigs();

    private static Map<String, ParserBenchmark<?>> makeConfigs() {
        Map<String, ParserBenchmark<?>> result = new HashMap<>();
        result.put("serial", new BenchmarkSerialParser());
        result.put("parallel-concurrent",new BenchmarkParallelParser());

        return result;
    }

    private static ParserBenchmark<?> makeBenchmark(String config) {
        final ParserBenchmark<?> parserBenchmark = configs.get(config);
        if (parserBenchmark!=null) {
            return parserBenchmark;
        }
        throw new RuntimeException("unknown benchmark type "+config);
    }

    private static Path rwExecPath() {
        Path f = Paths.get(FileTools.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        while (!Files.exists(f.resolve("Makefile"))) {
            f = f.getParent();
        }
        return f;

    }
    private static Stream<Job> submitJob(Path edif, String config, Path benchmarkDir) {
        try {
            Path relativeEdif = benchmarkDir.relativize(edif);

            Path runDir = relativeEdif.toAbsolutePath().resolve(config);
            if (Files.exists(runDir.resolve("0.txt"))) {
                return Stream.empty();
            }
            Files.createDirectories(runDir);
            final Job job = JobQueue.createJob();
            job.setCommand(rwExecPath().toString()+"/build/install/rapidwright/bin/rapidwright BenchmarkEdifLoad run " + edif + " "+config);
            job.setRunDir(runDir.toString());
            return Stream.of(job);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void submitLsf(Path benchmarkDir) throws IOException {
        try (Stream<Path> files = Files.walk(benchmarkDir)) {
            JobQueue queue = new JobQueue();
            files.filter(Files::isRegularFile)
                    .filter(f->f.getFileName().toString().endsWith(".edf"))
                    .flatMap(f->configs.keySet().stream().sorted().map(config->new Pair<>(f, config)))
                    .flatMap(p-> submitJob(p.getFirst(), p.getSecond(), benchmarkDir))
                    .forEach(queue::addJob);

            queue.runAllToCompletion();
        }
    }

    private static void results(Path benchmarkDir, PrintWriter pw) {
        final String serial = "serial";

        final List<String> configNames = configs.keySet().stream().sorted(Comparator.comparing(s->s.equals(serial)?"":s)).collect(Collectors.toList());

        List<Pair<String, Function<Stats,Double>>> metrics = Arrays.asList(
            new Pair<String, Function<Stats,Double>>("Runtime",(Function<Stats,Double>)s->s.runtime/1E9),
            new Pair<String, Function<Stats,Double>>("Parser Mem",(Function<Stats,Double>)s->s.parserMem/1E6),
            new Pair<String, Function<Stats,Double>>("EDIF Mem",(Function<Stats,Double>)s->s.edifMem/1E6)

        );

        Map<String, Double> metricValues = new HashMap<>();

        pw.print("Filename");
        for (Pair<String, Function<Stats, Double>> metric : metrics) {
            for (String configName : configNames) {
                pw.print(";");
                pw.print(configName+" "+metric.getFirst());
            }
        }
        pw.print(";threads;filesize");
        pw.println();
        try (Stream<Path> files = Files.walk(Paths.get("."))) {
            files.filter(Files::isDirectory)
                    .filter(f->Files.exists(f.resolve("serial").resolve("0.txt")))
                    .forEach(f-> {
                        Path edif = benchmarkDir.resolve(Paths.get(".").relativize(f));
                        long fileSize = 0;
                        try {
                            fileSize = Files.size(edif);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        final Map<String, Stats> stats = configNames.stream()
                                .collect(Collectors.toMap(Function.identity(), s -> Stats.read(f.resolve(s).resolve("0.txt"))));

                        final Set<String> edifChecksums = stats.values().stream().map(s -> s.checksumEdif).collect(Collectors.toSet());
                        if (edifChecksums.size()!=1) {
                            throw new RuntimeException("edifs not all equal");
                        }
                        final Set<String> binaryChecksums = stats.values().stream().map(s -> s.checksumBinaryEdif).collect(Collectors.toSet());
                        if (binaryChecksums.size()!=1) {
                            throw new RuntimeException("binary edifs not all equal");
                        }

                        for (Stats stat : stats.values()) {
                            if (stat.threads!=stat.successfulThreads) {
                                throw new RuntimeException("failed threads");
                            }
                        }

                        if (fileSize < 10*1024*1024) {
                            return;
                        }

                        pw.print(f);
                        for (Pair<String, Function<Stats, Double>> metric : metrics) {

                            for (String configName : configNames) {
                                pw.print(";");
                                final double currentVal = metric.getSecond().apply(stats.get(configName));
                                pw.print(currentVal);

                                final String fullName = configName + " " + metric.getFirst();
                                double oldVal = metricValues.getOrDefault(fullName,0.0);
                                metricValues.put(fullName, oldVal+currentVal);
                            }
                        }

                        pw.print(";");
                        pw.print(stats.get(configNames.get(1)).threads);
                        pw.print(";");
                        pw.print(fileSize/1E6);

                        pw.println();

                    });

            for (Pair<String, Function<Stats, Double>> metric : metrics) {
                System.out.println(metric.getFirst());
                System.out.println(metric.getFirst().replaceAll(".","="));
                double serialVal = metricValues.getOrDefault(serial+" "+metric.getFirst(),0.0);
                for (String configName : configNames) {
                    String fullName = configName+" "+metric.getFirst();
                    final double val = metricValues.getOrDefault(fullName, 0.0);
                    System.out.format("%30s: %10.3f", configName, val);
                    if (!configName.equals(serial)) {
                        System.out.format(" %10.3f%%", val/serialVal*100);
                    }
                    System.out.println();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        //MessageGenerator.waitOnAnyKey();
        if (args.length==0) {
            throw new RuntimeException("no mode given");
        }
        String mode = args[0];
        switch (mode) {
            case "lsf": {
                Path benchmarkDir = Paths.get(args[1]);
                submitLsf(benchmarkDir);
                break;
            }
            case "run":
            case "print":
                boolean isFullRun = mode.equals("run");


                Path input = Paths.get(args[1]);
                String config = args[2];

                Path outputDir = Paths.get(".");
                int i = 0;
                Path outputFile;
                while (Files.exists(outputFile = outputDir.resolve(i + ".txt"))) {
                    i++;
                }

                ParserBenchmark<?> benchmark = makeBenchmark(config);
                Stats stats = benchmark.benchmark(input, isFullRun);

                benchmark.freeNetlist();
                stats.print();
                if (isFullRun) {
                    stats.save(outputFile);
                }
                break;
            case "results": {
                Path benchmarkDir = Paths.get(args[1]);
                try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(args[2])))) {

                    results(benchmarkDir, pw);
                }
                break;
            }
            default:
                throw new RuntimeException("unknown mode " + mode);
        }
    }
}
