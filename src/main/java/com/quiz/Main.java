package com.quiz;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            runInteractive(new Scanner(System.in), System.out);
            return;
        }
        boolean ox = args.length > 0 && args[0].equals("--ox");
        if (args.length == 0 || (ox && args.length == 1)) {
            System.err.println("사용법: com.quiz.Main [--ox] samples/파일.pdf [samples/다른파일.pdf ...]");
            return;
        }

        PdfReader reader = new PdfReader();
        TermExtractor extractor = new TermExtractor();
        Scanner input = ox ? new Scanner(System.in) : null;
        for (int index = ox ? 1 : 0; index < args.length; index++) {
            String path = args[index];
            System.out.println("=== PDF: " + path + " ===");
            try {
                TermExtractor.Result result = extractor.extract(reader.readPages(path));
                if (ox) {
                    new QuizGenerator().runOx(result.terms(), input, System.out);
                    continue;
                }
                System.out.println("[Term 목록]");
                for (Term term : result.terms()) {
                    System.out.printf("[페이지 %d] %s: %s%n",
                            term.page(), term.term(), term.description());
                }
                System.out.println("[제외된 줄 경고]");
                for (TermExtractor.Warning warning : result.warnings()) {
                    System.out.printf("[페이지 %d] %s | 원문: %s%n",
                            warning.page(), warning.reason(), warning.line());
                }
                System.out.printf("요약: %s | Term 수: %d%n", path, result.terms().size());
            } catch (IOException | InvalidPathException e) {
                System.err.printf("오류: %s | %s%n", path, e.getMessage());
                System.out.printf("요약: %s | 추출 실패 (Term 수 확인 불가)%n", path);
            }
        }
    }

    static void runInteractive(Scanner input, PrintStream output) {
        runInteractive(input, output, new QuizRepository());
    }

    static void runInteractive(Scanner input, PrintStream output, QuizRepository repository) {
        runInteractive(input, output, repository,
                new QuizResultRepository(repository.directory().resolveSibling("results")));
    }

    static void runInteractive(Scanner input, PrintStream output, QuizRepository repository,
            QuizResultRepository results) {
        runInteractive(input, output, repository, results, Clock.systemUTC());
    }

    static void runInteractive(Scanner input, PrintStream output, QuizRepository repository,
            QuizResultRepository results, Clock clock) {
        while (true) {
            String[] sourcePdf = new String[1];
            TermExtractor.Result extracted = openPdf(input, output, repository, results, sourcePdf);
            if (extracted == null) return;
            if (!runMenu(extracted, sourcePdf[0], repository, results, input, output, clock)) return;
        }
    }

    private static TermExtractor.Result openPdf(Scanner input, PrintStream output,
            QuizRepository repository, QuizResultRepository results, String[] sourcePdf) {
        while (true) {
            output.println("PDF 경로를 입력하세요(파일을 끌어다 놓아도 됩니다)");
            output.println("저장된 문제는 load, 학습 결과/오답 복습은 review를 입력하세요.");
            if (!input.hasNextLine()) return null;
            String path = input.nextLine().strip();
            if (path.length() >= 2 && ((path.startsWith("\"") && path.endsWith("\""))
                    || (path.startsWith("'") && path.endsWith("'")))) {
                path = path.substring(1, path.length() - 1).strip();
            }
            if (path.equalsIgnoreCase("load")) {
                loadSaved(repository, results, input, output);
                continue;
            }
            if (path.equalsIgnoreCase("review")) {
                reviewSaved(repository, results, input, output);
                continue;
            }
            try {
                if (!path.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    output.println(".pdf 파일 경로를 입력하세요.");
                    continue;
                }
                if (!Files.isRegularFile(Path.of(path))) {
                    output.println("파일이 없습니다. PDF 파일 경로를 확인하세요.");
                    continue;
                }
                TermExtractor.Result extracted = new TermExtractor().extract(new PdfReader().readPages(path));
                output.printf("용어 %d개를 찾았습니다%n", extracted.terms().size());
                if (extracted.terms().size() < 2) {
                    output.println("내용이 부족합니다. '용어 → : 설명' 패턴의 용어가 2개 이상 필요합니다. 다른 PDF를 입력하세요.");
                    continue;
                }
                sourcePdf[0] = Path.of(path).toAbsolutePath().normalize().toString();
                return extracted;
            } catch (PdfReader.InputLimitException e) {
                output.println(e.getMessage());
            } catch (IOException | InvalidPathException e) {
                output.println("PDF를 열 수 없습니다: " + e.getMessage());
            }
        }
    }

    /** true면 다른 PDF를 열고, false면 프로그램을 종료한다. */
    private static boolean runMenu(TermExtractor.Result extracted, String sourcePdf,
            QuizRepository repository, QuizResultRepository results, Scanner input, PrintStream output, Clock clock) {
        List<Term> terms = extracted.terms();
        while (true) {
            output.println("1.O/X 퀴즈  2.용어 목록 보기  3.다른 PDF 열기  4.객관식 퀴즈  5.문제 세트 저장  6.저장된 문제 불러오기  7.학습 결과/오답 복습  0.종료");
            if (!input.hasNextLine()) return false;
            int choice;
            try {
                choice = Integer.parseInt(input.nextLine().strip());
            } catch (NumberFormatException e) {
                output.println("메뉴 번호를 숫자로 입력하세요.");
                continue;
            }
            switch (choice) {
                case 0: return false;
                case 1:
                    QuizGenerator oxGenerator = new QuizGenerator();
                    saveAndPlay(SavedQuizSet.fromGenerated(sourcePdf, extracted,
                            oxGenerator.generateOx(terms), List.of()), repository, results, input, output, clock);
                    break;
                case 2:
                    for (Term term : terms) {
                        output.printf("[페이지 %d] %s: %s%n", term.page(), term.term(), term.description());
                    }
                    break;
                case 3: return true;
                case 4:
                    QuizGenerator choiceGenerator = new QuizGenerator();
                    QuizGenerator.MultipleChoiceResult generated = choiceGenerator.generateMultipleChoice(extracted);
                    for (String reason : generated.skipped())
                        output.println(reason.startsWith("복원:") || reason.startsWith("제외/검토 필요:")
                                ? reason : "건너뜀: " + reason);
                    saveAndPlay(SavedQuizSet.fromGenerated(sourcePdf, extracted, List.of(),
                            generated.questions()), repository, results, input, output, clock);
                    break;
                case 5:
                    saveGenerated(extracted, sourcePdf, repository, output, clock);
                    break;
                case 6:
                    loadSaved(repository, results, input, output);
                    break;
                case 7:
                    reviewSaved(repository, results, input, output);
                    break;
                default: output.println("0~7 사이의 메뉴 번호를 입력하세요.");
            }
        }
    }
    private static void saveGenerated(TermExtractor.Result extracted, String sourcePdf,
            QuizRepository repository, PrintStream output, Clock clock) {
        QuizGenerator generator = new QuizGenerator();
        SavedQuizSet set = SavedQuizSet.fromGenerated(sourcePdf, extracted,
                generator.generateOx(extracted.terms()),
                generator.generateMultipleChoice(extracted).questions())
                .forSave(QuizMetadata.defaultTitle(sourcePdf), "", clock.instant());
        try {
            Path path = repository.save(set);
            output.printf("문제 세트 저장: %s (O/X %d개, 4지선다 %d개)%n",
                    path, set.questions().stream().filter(q -> q.type() == SavedQuestion.Type.OX).count(),
                    set.questions().stream().filter(q -> q.type() == SavedQuestion.Type.MULTIPLE_CHOICE).count());
        } catch (IOException e) {
            output.println("문제 세트를 저장할 수 없습니다: " + e.getMessage());
        }
    }

    private static void loadSaved(QuizRepository repository, QuizResultRepository results,
            Scanner input, PrintStream output) {
        output.print("불러올 JSON 파일 이름 또는 경로: ");
        if (!input.hasNextLine()) return;
        try {
            SavedQuizSet set = repository.load(input.nextLine());
            output.printf("문제 세트 불러옴: %d개 (%s)%n", set.questions().size(), set.sourcePdf());
            playAndSave(set, set.questions(), null, results, input, output);
        } catch (IOException e) {
            output.println("문제 세트를 불러올 수 없습니다: " + e.getMessage());
        }
    }

    private static void saveAndPlay(SavedQuizSet set, QuizRepository repository,
            QuizResultRepository results, Scanner input, PrintStream output, Clock clock) {
        set = set.forSave(QuizMetadata.defaultTitle(set.sourcePdf()), "", clock.instant());
        try {
            output.println("문제 세트 저장: " + repository.save(set));
            playAndSave(set, set.questions(), null, results, input, output);
        } catch (IOException e) {
            output.println("문제 세트를 저장할 수 없습니다: " + e.getMessage());
        }
    }

    private static void playAndSave(SavedQuizSet set, List<SavedQuestion> questions,
            String reviewOf, QuizResultRepository results, Scanner input, PrintStream output) {
        QuizResult result = new QuizGenerator().playSavedQuestions(set, questions,
                input, output, Clock.systemUTC(), reviewOf);
        try {
            output.println("학습 결과 저장: " + results.save(result, set));
        } catch (IOException e) {
            output.println("학습 결과를 저장할 수 없습니다: " + e.getMessage());
        }
    }

    private static void reviewSaved(QuizRepository repository, QuizResultRepository results,
            Scanner input, PrintStream output) {
        output.print("문제 세트 JSON 파일 이름 또는 경로: ");
        if (!input.hasNextLine()) return;
        String quizName = input.nextLine();
        output.print("학습 결과 JSON 파일 이름 또는 경로: ");
        if (!input.hasNextLine()) return;
        String resultName = input.nextLine();
        try {
            SavedQuizSet set = repository.load(quizName);
            QuizResult previous = results.load(resultName, set);
            output.println("학습 결과 불러옴: " + previous.id());
            new QuizGenerator().printSummary(set, previous.totalQuestions(), previous, output);
            List<SavedQuestion> wrong = previous.wrongQuestions(set);
            if (wrong.isEmpty()) {
                output.println("복습할 오답이 없습니다.");
                return;
            }
            output.printf("오답 복습: %d개%n", wrong.size());
            playAndSave(set, wrong, previous.id(), results, input, output);
        } catch (IOException e) {
            output.println("학습 결과를 불러올 수 없습니다: " + e.getMessage());
        }
    }
}
