package org.cryptomator.sanitizer.integrity.problems;

import static java.util.Collections.unmodifiableSet;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class Problems {

	private final Set<Problem> problems = new HashSet<>();

	private final Optional<Path> pathToVault;

	public Problems(Path pathToVault) {
		this.pathToVault = Optional.of(pathToVault);
	}

	public Problems() {
		this.pathToVault = Optional.empty();
	}

	public void report(Problem problem) {
		problems.add(problem);
	}

	public Set<Problem> asSet() {
		return unmodifiableSet(problems);
	}

	public void reportMissingMFile(Path file, Path mFile) {
		report(new MissingMFileProblem(sensitive(file), sensitive(mFile)));
	}

	public void reportOrphanMFile(Path mFile) {
		report(new OrphanMFileProblem(sensitive(mFile)));
	}

	public void reportException(Exception e) {
		report(new ExceptionProblem(sensitive(e)));
	}

	public void reportSuspectFile(Path child) {
		report(new SuspectFileProblem(sensitive(child)));
	}

	public void reportFileContentProblem(Path path, String expected, String actual) {
		report(new FileContentProblem(sensitive(path), expected, actual));
	}

	public void reportSizeMismatch(Path path, String expectedSize, long actualSize) {
		report(new FileSizeMismatchProblem(sensitive(path), expectedSize, actualSize));
	}

	public void reportFileWithMissingEqualsSign(Path path) {
		report(new FileWithMissingEqualsSignProblem(sensitive(path)));
	}

	public void reportConflict(Path path) {
		report(new ConflictProblem(sensitive(path)));
	}

	public void reportNameProblem(String pattern, Path path) {
		report(new NameProblem(pattern, sensitive(path)));
	}

	public void reportLowercasedFile(Path path) {
		report(new LowercasedFileProblem(sensitive(path)));
	}

	public void reportUppercasedFile(Path path) {
		report(new UppercasedFileProblem(sensitive(path)));
	}

	public void reportMissingMasterkeyFile(Path path) {
		report(new MissingFileProblem(sensitive(path), Severity.FATAL));
	}

	public void reportMissingFile(Path path) {
		report(new MissingFileProblem(sensitive(path), Severity.ERROR));
	}

	public void reportMissingFile(Path dir, String name) {
		report(new MissingFileProblem(sensitive(dir), name, Severity.ERROR));
	}

	public void reportMissingDirectory(Path path, Path dirfile, boolean exists) {
		report(new MissingDirectoryProblem(sensitive(path), sensitive(dirfile), exists));
	}

	public void reportOrphanDirectory(Path path) {
		report(new OrphanDirectoryProblem(sensitive(path)));
	}

	public void reportInvalidMasterkeyBackupFile(Path path) {
		report(new InvalidMasterkeyFile(sensitive(path), Severity.WARN));
	}

	public void reportInvalidMasterkeyFile(Path path) {
		report(new InvalidMasterkeyFile(sensitive(path), Severity.FATAL));
	}

	public void reportRootDirectoryExists(Path path) {
		report(new RootDirectoryInfo(sensitive(path)));
	}

	private Sensitive<Exception> sensitive(Exception e) {
		return new Sensitive<Exception>() {
			@Override
			public Exception get() {
				return e;
			}

			@Override
			public String toString() {
				StringWriter traceWriter = new StringWriter();
				e.printStackTrace(new PrintWriter(traceWriter));
				String trace = traceWriter.toString();
				return pathToVault //
						.map(pathToVault -> trace.replace(pathToVault.toString(), "<vault>")) //
						.orElse(trace);
			}
		};
	}

	private Sensitive<Path> sensitive(Path path) {
		return new Sensitive<Path>() {
			@Override
			public Path get() {
				return path;
			}

			@Override
			public String toString() {
				return pathToVault //
						.map(pathToVault -> pathToVault.relativize(path).toString()) //
						.orElseGet(() -> path.toString());
			}
		};
	}

}