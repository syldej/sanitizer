package org.cryptomator.sanitizer;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.newBufferedWriter;
import static java.nio.file.Files.size;
import static java.nio.file.Files.walk;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Collections.sort;
import static java.util.stream.Collectors.toList;
import static org.cryptomator.sanitizer.integrity.problems.Severity.INFO;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.cryptomator.cryptolib.api.InvalidPassphraseException;
import org.cryptomator.sanitizer.integrity.AbortCheckException;
import org.cryptomator.sanitizer.integrity.IntegrityCheck;
import org.cryptomator.sanitizer.integrity.problems.Problem;
import org.cryptomator.sanitizer.integrity.problems.Severity;
import org.cryptomator.sanitizer.integrity.problems.SolutionContext;
import org.cryptomator.sanitizer.restorer.PathEncryptor;
import org.cryptomator.sanitizer.utils.Counter;

public class Sanitizer {

	private static final long KIBI = 1024;
	private static final String[] KIBI_POWERS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB", "ZiB", "YiB"};

	public static void main(String[] stringArgs) {
		print("# Cryptomator vault sanitizer v" + Version.get() + " #");
		print();
		Args.parse(stringArgs).ifPresent(Sanitizer::main);
	}

	public static void main(Args args) {
		switch (args.command()) {
		case "check":
		case "deepcheck":
			integrityCheck(args, "deepCheck".equalsIgnoreCase(args.command()));
			break;
		case "encryptpath":
			encryptPath(args);
			break;
		default:
			System.err.println("Unknown command");
			Args.printUsage();
		}
	}

	private static void integrityCheck(Args args, boolean deepCheck) {
		try (CryptorHolder cryptorHolder = new CryptorHolder(); //
				Passphrase passphrase = args.passphrase()) {
			IntegrityCheck integrityCheck = new IntegrityCheck(cryptorHolder);
			print("Scanning vault structure may take some time. Be patient...");

			writeStructureToOutput(args, args.vaultLocation());

			print("Checking the vault may take some time. Be patient...");
			print();

			Set<Problem> problems = integrityCheck.check(args.vaultLocation(), passphrase, deepCheck);
			writeResultsToConsole(args, problems);
			writeProblemsToOutput(args, problems);
			maybeSolveProblems(args, cryptorHolder, problems);

			print();
			print("Done.");
		} catch (AbortCheckException e) {
			printNoNewline("Check failed: ");
			print(e.getMessage());
		}
	}

	private static void encryptPath(Args args) {
		try (Passphrase passphrase = args.passphrase()) {
			PathEncryptor.encryptPath(args.vaultLocation(), passphrase);
		} catch (InvalidPassphraseException e) {
			System.err.println("Invalid passphrase.");
		} catch (AbortCheckException e) {
			System.err.println(e.getMessage());
		} catch (IOException e) {
			System.err.println(e.getMessage());
		}
	}

	private static void maybeSolveProblems(Args args, CryptorHolder cryptorHolder, Set<Problem> problems) {
		if (cryptorHolder.optionalCryptor().isPresent()) {
			List<Problem> problemsToSolve = problems.stream() //
					.filter(problem -> args.problemsToSolve().contains(problem.name())) //
					.collect(toList());
			if (!problemsToSolve.isEmpty()) {
				print();
				print("Solving problems. This may take some time. Be patient...");
				print();
				SolutionContext context = SolutionContext.executePrintingTo(args.vaultLocation(), cryptorHolder.optionalCryptor().get(), System.out);
				problemsToSolve.forEach(problem -> problem.solution().ifPresent(solution -> solution.execute(context)));
			}
		}
	}

	private static void writeStructureToOutput(Args args, Path vaultLocation) {
		Counter counter = new Counter();
		try (PrintWriter writer = new PrintWriter(newBufferedWriter(args.structureOutputFile(), UTF_8, CREATE_NEW, WRITE)); //
				Stream<Path> vaultContents = walk(vaultLocation)) {
			vaultContents.forEach(writePathToOutput(args, writer) //
					.andThen(ignored -> counter.increment()));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		print("Wrote structure to " + args.structureOutputFile() + ".");
		print(counter.get() + " files in vault");
		print();
	}

	private static Consumer<Path> writePathToOutput(Args args, PrintWriter writer) {
		return path -> {
			try {
				Path relativePath = args.vaultLocation().relativize(path);
				if (isDirectory(path)) {
					writer.println(format("d %s", relativePath));
				} else if (isRegularFile(path)) {
					writer.println(format("f %s %s", relativePath, obfuscateSize(size(path))));
				} else {
					writer.println(format("? %s", relativePath));
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	private static String obfuscateSize(long size) {
		int i = 0;
		while (i < 8 && size > KIBI) {
			size = size / KIBI;
			i++;
		}
		if (i == 0) {
			return size + " " + KIBI_POWERS[i];
		} else {
			return "~" + size + " " + KIBI_POWERS[i];
		}
	}

	private static void writeResultsToConsole(Args args, Set<Problem> problems) {
		print("Found " + countProblems(problems) + " problem(s):");
		for (Severity severity : Severity.values()) {
			print("* " + problems.stream().filter(problem -> problem.severity() == severity).count() + " " + severity);
		}
		print();
		print("See " + args.checkOutputFile() + " for details.");
	}

	private static void writeProblemsToOutput(Args args, Set<Problem> problems) {
		try (PrintWriter writer = new PrintWriter(newBufferedWriter(args.checkOutputFile(), UTF_8, CREATE_NEW, WRITE))) {
			writer.println(countProblems(problems) + " problem(s) found.");
			if (problems.isEmpty()) {
				return;
			}
			List<Problem> sortedProblems = new ArrayList<>(problems);
			sort(sortedProblems, (p1, p2) -> {
				int bySeverity = p1.severity().ordinal() - p2.severity().ordinal();
				if (bySeverity == 0) {
					return p1.toString().compareTo(p2.toString());
				} else {
					return bySeverity;
				}
			});
			sortedProblems.forEach(problem -> {
				writer.print(format("%-5s", problem.severity()));
				writer.print(' ');
				writer.println(problem);
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static long countProblems(Set<Problem> problems) {
		return problems.stream().filter(problem -> problem.severity() != INFO).count();
	}

	private static void print() {
		System.out.println();
	}

	private static void printNoNewline(String line) {
		System.out.print(line);
	}

	private static void print(String line) {
		System.out.println(line);
	}

}