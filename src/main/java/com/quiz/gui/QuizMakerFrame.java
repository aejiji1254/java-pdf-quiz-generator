package com.quiz.gui;

import com.quiz.AnswerRecord;
import com.quiz.QuizResult;
import com.quiz.SavedQuestion;
import com.quiz.SavedQuizSet;
import com.quiz.ManualTermSet;
import com.quiz.Term;
import com.quiz.QuizMetadata;
import com.quiz.QuizRepository;
import com.quiz.QuizTransfer;
import com.quiz.GenerationOptions;
import com.quiz.TermExtractor;
import com.quiz.PdfPageService;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

/** CardLayout 기반 데스크톱 화면. 모든 컴포넌트 변경은 EDT에서 수행한다. */
public final class QuizMakerFrame extends JFrame {
    private final QuizWorkflow workflow;
    private final QuizTransferDialogs transferDialogs;
    private final NotebookDialogs notebookDialogs;
    interface PdfInput {
        Path choose() throws IOException;
        Path validate(Path file) throws IOException;
        int pageCount(Path file) throws IOException;
        QuizWorkflow.Preview generate(Path file, int first, int last, GenerationOptions options, TermExtractor.Mode mode) throws Exception;
    }
    private final PdfInput pdfInput;
    private final NotebookQueue notebookQueue = new NotebookQueue();
    private boolean notebookSession;
    private int notebookGeneration;
    private SwingWorker<com.quiz.WrongAnswerNotebook.Snapshot, Void> notebookWorker;
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JTextArea mainStatus = textArea();
    private final JLabel counts = new JLabel("O/X 0개 · 4지선다 0개");
    private final JButton choosePdfButton = new JButton("PDF 선택");
    private final JButton generateButton = new JButton("문제 생성");
    private final JButton loadQuizButton = new JButton("저장된 문제 불러오기");
    private final JButton loadReviewButton = new JButton("오답 복습");
    private final JButton notebookButton = new JButton("오답노트");
    private final JButton startButton = new JButton("퀴즈 시작");
    private final JButton manualButton = new JButton("수동 용어 입력");
    private final JSpinner firstPage = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JSpinner lastPage = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JLabel pageCountLabel = new JLabel("PDF 페이지 수: 미선택");
    private final GenerationOptionsPanel pdfOptions = new GenerationOptionsPanel();
    private final JComboBox<TermExtractor.Mode> extractionMode = new JComboBox<>(TermExtractor.Mode.values());
    private final GenerationOptionsPanel manualOptions = new GenerationOptionsPanel();
    private final JTextField manualTermField = new JTextField(28);
    private final JTextArea manualDescription = new JTextArea(4, 28);
    private final DefaultListModel<Term> manualModel = new DefaultListModel<>();
    private final JList<Term> manualList = new JList<>(manualModel);
    private final JTextArea manualStatus = textArea();
    private final DefaultListModel<SavedQuestion> reviewModel = new DefaultListModel<>();
    private final JList<SavedQuestion> reviewList = new JList<>(reviewModel);
    private final JTextArea reviewDetails = textArea();
    private final PdfSourcePanel sourceView;
    private final JTextArea generationDetails = textArea();
    private final JCheckBox excludedBox = new JCheckBox("이 문제를 출제에서 제외");
    private final JLabel reviewCount = new JLabel();
    private final JButton saveReviewedButton = new JButton("선택한 문제 세트 저장");
    private final JLabel quizProgressText = new JLabel();
    private final JProgressBar quizProgress = new JProgressBar();
    private final JTextArea questionText = textArea();
    private final JTextArea oxDescription = textArea();
    private final JScrollPane oxDescriptionScroll = new JScrollPane(oxDescription);
    private final JPanel choices = new JPanel();
    private final JTextArea feedback = textArea();
    private final JButton nextButton = new JButton("다음 문제");
    private final JButton endButton = new JButton("중간 종료 및 결과 저장");
    private final JTextArea resultDetails = textArea();
    private final Set<String> excluded = new HashSet<>();
    private ButtonGroup choiceGroup = new ButtonGroup();
    private Path selectedPdf;
    private Path explicitlyChosenPdf;
    private int pdfSelectionVersion;
    private int generationVersion;
    private int selectedPageCount;
    private ManualTermSet manualSet;
    private QuizWorkflow.Preview preview;
    private SavedQuizSet revisionOriginal;
    private SavedQuizSet currentSet;
    private QuizSession session;
    private QuizResult lastResult;
    private boolean busy;
    private SwingWorker<QuizWorkflow.Preview, Void> generationWorker;
    private SwingWorker<Integer, Void> pageWorker;
    private SwingWorker<QuizRepository.Listing, Void> catalogWorker;
    private SwingWorker<SavedQuizSet, Void> loadWorker;
    private SwingWorker<?, ?> transferWorker;
    private final QuizLibraryPanel library = new QuizLibraryPanel(this::loadListedQuiz,
            () -> cards.show(root, "main"), this::exportListedQuiz, this::importListedQuiz);
    private final WrongAnswerNotebookPanel notebookPanel = new WrongAnswerNotebookPanel(this::startNotebook, this::showNotebookHome);

    public QuizMakerFrame(QuizWorkflow workflow) {
        this(workflow, QuizTransferDialogs.swing());
    }

    QuizMakerFrame(QuizWorkflow workflow, QuizTransferDialogs transferDialogs) {
        this(workflow, transferDialogs, NotebookDialogs.swing());
    }

    QuizMakerFrame(QuizWorkflow workflow, QuizTransferDialogs transferDialogs, NotebookDialogs notebookDialogs) {
        this(workflow, transferDialogs, notebookDialogs, new PdfPageService(), null);
    }

    QuizMakerFrame(QuizWorkflow workflow, PdfPageService pages, PdfInput input) {
        this(workflow, QuizTransferDialogs.swing(), NotebookDialogs.swing(), pages, input);
    }

    private QuizMakerFrame(QuizWorkflow workflow, QuizTransferDialogs transferDialogs, NotebookDialogs notebookDialogs,
            PdfPageService pages, PdfInput input) {
        super("PDF Quiz Maker");
        this.workflow = workflow;
        this.transferDialogs = transferDialogs;
        this.notebookDialogs = notebookDialogs;
        this.sourceView = new PdfSourcePanel(pages, this::addFromSource);
        this.pdfInput = input == null ? new PdfInput() {
            public Path choose() throws IOException { return chooseFile(null); }
            public Path validate(Path file) throws IOException { return workflow.validatePdfSelection(file); }
            public int pageCount(Path file) throws IOException { return new com.quiz.PdfReader().pageCount(file); }
            public QuizWorkflow.Preview generate(Path file, int first, int last, GenerationOptions options, TermExtractor.Mode mode) throws IOException {
                return workflow.generate(file, first, last, options, mode);
            }
        } : input;
        extractionMode.setSelectedItem(TermExtractor.Mode.LEGACY);
        extractionMode.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value == TermExtractor.Mode.STRUCTURED) setText("STRUCTURED (실험적 — 원문 대조 필요)");
                return this;
            }
        });
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(720, 560));
        setSize(900, 680);
        setLocationRelativeTo(null);
        quizProgress.setStringPainted(true);
        mainStatus.setRows(2);
        mainStatus.setText("PDF를 선택하거나 저장된 문제 세트를 불러오세요.");
        root.add(mainCard(), "main");
        root.add(reviewCard(), "review");
        root.add(manualCard(), "manual");
        root.add(quizCard(), "quiz");
        root.add(resultCard(), "result");
        root.add(library, "library");
        root.add(notebookPanel, "notebook");
        setContentPane(root);
        cards.show(root, "main");
        startButton.setEnabled(false);
        generateButton.setEnabled(false);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (session != null) {
                    Object[] choices = {"저장 후 종료", "저장하지 않고 종료", "취소"};
                    int selected = JOptionPane.showOptionDialog(QuizMakerFrame.this,
                            "진행 중인 퀴즈 결과를 저장하시겠습니까?", "종료 확인",
                            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                            null, choices, choices[0]);
                    QuizWorkflow.CloseChoice decision = switch (selected) {
                        case 0 -> QuizWorkflow.CloseChoice.SAVE;
                        case 1 -> QuizWorkflow.CloseChoice.DISCARD;
                        default -> QuizWorkflow.CloseChoice.CANCEL;
                    };
                    try {
                        if (!workflow.closeSession(session, decision)) return;
                    } catch (IOException e) {
                        showError("학습 결과를 저장할 수 없습니다. 창을 유지합니다.", e);
                        return;
                    }
                    session = null;
                }
                if (generationWorker != null && !generationWorker.isDone())
                    generationWorker.cancel(true);
                if (pageWorker != null && !pageWorker.isDone()) pageWorker.cancel(true);
                if (catalogWorker != null) catalogWorker.cancel(true);
                if (loadWorker != null) loadWorker.cancel(true);
                if (transferWorker != null) transferWorker.cancel(true);
                if (notebookWorker != null) notebookWorker.cancel(true);
                notebookQueue.clear();
                dispose();
            }
        });
    }

    private JPanel mainCard() {
        JPanel panel = page();
        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        choosePdfButton.addActionListener(event -> choosePdf());
        generateButton.addActionListener(event -> generate());
        loadQuizButton.addActionListener(event -> loadQuiz());
        startButton.addActionListener(event -> beginQuiz());
        loadReviewButton.addActionListener(event -> loadReview());
        notebookButton.addActionListener(event -> openNotebook());
        manualButton.addActionListener(event -> openManual());
        for (JButton button : new JButton[] {choosePdfButton, generateButton,
                manualButton, loadQuizButton, startButton, loadReviewButton, notebookButton}) {
            button.setAlignmentX(LEFT_ALIGNMENT);
            actions.add(button);
        }
        panel.add(new JLabel("PDF 문제 세트"), BorderLayout.NORTH);
        panel.add(actions, BorderLayout.CENTER);
        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        JPanel range = new JPanel(new FlowLayout(FlowLayout.LEFT));
        range.add(pageCountLabel);
        range.add(new JLabel("시작 페이지"));
        range.add(firstPage);
        range.add(new JLabel("종료 페이지"));
        range.add(lastPage);
        footer.add(range);
        JPanel extractionOptions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        extractionOptions.add(new JLabel("PDF 추출 방식")); extractionOptions.add(extractionMode); footer.add(extractionOptions);
        footer.add(pdfOptions);
        footer.add(counts);
        footer.add(new JScrollPane(mainStatus, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel manualCard() {
        JPanel panel = page();
        panel.add(new JLabel("수동 용어·설명 — PDF 원문과 일치하는지 직접 확인하세요"), BorderLayout.NORTH);
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel("용어"));
        form.add(manualTermField);
        form.add(new JLabel("설명"));
        manualDescription.setLineWrap(true);
        manualDescription.setWrapStyleWord(true);
        form.add(new JScrollPane(manualDescription));
        JButton add = new JButton("항목 추가");
        add.addActionListener(event -> addManualTerm());
        JButton remove = new JButton("선택 항목 삭제");
        remove.addActionListener(event -> removeManualTerm());
        JPanel edit = new JPanel(new FlowLayout(FlowLayout.LEFT));
        edit.add(add);
        edit.add(remove);
        form.add(edit);
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(form, BorderLayout.NORTH);
        manualList.setCellRenderer((list, value, index, selected, focused) -> {
            JLabel label = new JLabel((index + 1) + ". " + value.term() + " — " + value.description());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        center.add(new JScrollPane(manualList), BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        manualStatus.setRows(3);
        bottom.add(new JScrollPane(manualStatus), BorderLayout.CENTER);
        bottom.add(manualOptions, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton save = new JButton("수동 항목 JSON 저장");
        save.addActionListener(event -> saveManual());
        JButton load = new JButton("수동 항목 JSON 불러오기");
        load.addActionListener(event -> loadManual());
        JButton fresh = new JButton("새 수동 세트");
        fresh.addActionListener(event -> {
            manualSet = workflow.newManualSet(selectedPdf);
            refreshManual();
        });
        JButton make = new JButton("수동 항목으로 문제 생성");
        make.addActionListener(event -> generateManual());
        JButton back = new JButton("메인으로");
        back.addActionListener(event -> cards.show(root, "main"));
        for (JButton button : new JButton[] {save, load, fresh, make, back}) actions.add(button);
        bottom.add(actions, BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel reviewCard() {
        JPanel panel = page();
        JTextArea reviewHeading = textArea();
        reviewHeading.setRows(2);
        reviewHeading.setText("문제 검토 — 문항을 선택해 질문·선택지·정답·해설을 편집하거나 제외하세요.");
        generationDetails.setRows(6);
        generationDetails.setText(reviewHeading.getText());
        panel.add(new JScrollPane(generationDetails), BorderLayout.NORTH);
        reviewList.setCellRenderer((list, value, index, selected, focused) -> {
            String prefix = excluded.contains(value.id()) ? "[제외] " : "";
            JTextArea cell = reviewCell(prefix + (index + 1) + ". "
                    + (value.type() == SavedQuestion.Type.OX ? "O/X " : "4지선다 ")
                    + value.question(), list.getWidth());
            cell.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            cell.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return cell;
        });
        reviewList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showReviewDetails();
        });
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(new JScrollPane(reviewList, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        JPanel detail = new JPanel(new BorderLayout());
        javax.swing.JTabbedPane detailTabs = new javax.swing.JTabbedPane();
        detailTabs.addTab("문항 상세", new JScrollPane(reviewDetails));
        detailTabs.addTab("원문 보기", sourceView);
        detail.add(detailTabs, BorderLayout.CENTER);
        excludedBox.addActionListener(event -> {
            SavedQuestion question = reviewList.getSelectedValue();
            if (question == null) return;
            if (excludedBox.isSelected()) excluded.add(question.id());
            else excluded.remove(question.id());
            reviewList.repaint();
            updateReviewCount();
        });
        JPanel reviewActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        reviewActions.add(excludedBox);
        JButton edit = new JButton("선택한 문제 편집");
        edit.addActionListener(event -> editReviewedQuestion());
        reviewActions.add(edit);
        detail.add(reviewActions, BorderLayout.SOUTH);
        detail.setPreferredSize(new Dimension(300, 300));
        center.add(detail, BorderLayout.SOUTH);
        panel.add(center, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(reviewCount, BorderLayout.NORTH);
        JPanel reviewButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        saveReviewedButton.addActionListener(event -> saveReviewed());
        reviewButtons.add(saveReviewedButton);
        JButton add = new JButton("문제 추가");
        add.addActionListener(event -> addReviewedQuestion());
        reviewButtons.add(add);
        JButton back = new JButton("메인으로");
        back.addActionListener(event -> cards.show(root, "main"));
        reviewButtons.add(back);
        JButton manual = new JButton("문제가 부족하면 수동 입력");
        manual.addActionListener(event -> openManual());
        reviewButtons.add(manual);
        footer.add(reviewButtons, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel quizCard() {
        JPanel panel = page();
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(quizProgressText, BorderLayout.NORTH);
        top.add(quizProgress, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(8, 8));
        questionText.setRows(6);
        oxDescription.setRows(3);
        JPanel prompt = new JPanel(new BorderLayout(4, 4));
        prompt.add(new JScrollPane(questionText), BorderLayout.NORTH);
        prompt.add(oxDescriptionScroll, BorderLayout.CENTER);
        center.add(prompt, BorderLayout.NORTH);
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        center.add(new JScrollPane(choices, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        feedback.setRows(5);
        center.add(new JScrollPane(feedback), BorderLayout.SOUTH);
        panel.add(center, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        nextButton.addActionListener(event -> nextQuestion());
        endButton.addActionListener(event -> { notebookQueue.clear(); finishQuiz(); });
        footer.add(nextButton);
        footer.add(endButton);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel resultCard() {
        JPanel panel = page();
        panel.add(new JLabel("학습 결과"), BorderLayout.NORTH);
        panel.add(new JScrollPane(resultDetails), BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton home = new JButton("메인으로");
        home.addActionListener(event -> showNotebookHome());
        footer.add(home);
        JButton reviewWrong = new JButton("이번 오답 복습");
        reviewWrong.addActionListener(event -> reviewLastResult());
        footer.add(reviewWrong);
        JButton notebook = new JButton("오답노트로");
        notebook.addActionListener(event -> openNotebook()); footer.add(notebook);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void openManual() {
        if (manualSet == null) manualSet = workflow.newManualSet(selectedPdf);
        refreshManual();
        cards.show(root, "manual");
    }

    private void refreshManual() {
        manualModel.clear();
        for (Term term : manualSet.terms()) manualModel.addElement(term);
        manualStatus.setText(manualSet.sourceLabel() + "\n" + manualSet.terms().size()
                + "개 입력됨. PDF 원문과 일치하는지는 사용자가 검토해야 합니다.");
    }

    private void addManualTerm() {
        try {
            manualSet = manualSet.add(manualTermField.getText(), manualDescription.getText());
            manualTermField.setText("");
            manualDescription.setText("");
            refreshManual();
        } catch (IllegalArgumentException e) {
            showError("수동 항목을 추가할 수 없습니다.", e);
        }
    }

    private void removeManualTerm() {
        if (manualList.getSelectedIndex() < 0) return;
        manualSet = manualSet.remove(manualList.getSelectedIndex());
        refreshManual();
    }

    private void saveManual() {
        try {
            Path saved = workflow.saveManual(manualSet);
            manualStatus.setText("수동 항목 저장 완료: " + saved);
        } catch (IOException e) {
            showError("수동 항목을 저장할 수 없습니다.", e);
        }
    }

    private void loadManual() {
        Path file = chooseFile(Path.of("data", "manual-terms"));
        if (file == null) return;
        try {
            manualSet = workflow.loadManual(file);
            refreshManual();
        } catch (IOException e) {
            showError("수동 항목을 불러올 수 없습니다.", e);
        }
    }

    private void generateManual() {
        if (manualSet.terms().isEmpty()) {
            JOptionPane.showMessageDialog(this, "용어와 설명을 먼저 추가하세요.");
            return;
        }
        try { showPreview(workflow.generateManual(manualSet, manualOptions.options())); }
        catch (IllegalArgumentException e) { showError("생성 옵션을 확인하세요.", e); }
    }

    private void showPreview(QuizWorkflow.Preview generated) {
        showPreview(generated, null);
    }

    private void showPreview(QuizWorkflow.Preview generated, SavedQuizSet original) {
        preview = generated;
        revisionOriginal = original;
        saveReviewedButton.setText(original == null ? "선택한 문제 세트 저장" : "수정본 저장");
        generationDetails.setText(preview.diagnostics());
        generationDetails.setCaretPosition(0);
        excluded.clear();
        reviewModel.clear();
        for (SavedQuestion question : preview.set().questions()) reviewModel.addElement(question);
        if (!reviewModel.isEmpty()) reviewList.setSelectedIndex(0);
        updateReviewCount();
        mainStatus.setText(original != null ? "저장된 세트를 검토 중입니다. 수정본은 새 세트로 저장됩니다."
                : reviewModel.isEmpty()
                ? "문제 생성 0개. 검토 화면에서 문제를 직접 추가하거나 수동 용어를 입력할 수 있습니다."
                : "생성 완료. 검토 화면에서 출제 문항을 확인하세요.");
        cards.show(root, "review");
    }

    private void choosePdf() {
        if (busy && generationWorker == null) return;
        try {
            Path file = pdfInput.choose();
            if (file == null) return;
            Path chosen = pdfInput.validate(file);
            invalidateGeneration();
            final int selectionToken = ++pdfSelectionVersion;
            setBusy(true);
            mainStatus.setText("PDF 페이지 수를 확인하는 중...");
            pageWorker = new SwingWorker<Integer, Void>() {
                @Override protected Integer doInBackground() throws Exception {
                    return pdfInput.pageCount(chosen);
                }
                @Override protected void done() {
                    if (selectionToken != pdfSelectionVersion || pageWorker != this) return;
                    try {
                        if (isCancelled() || !isDisplayable()) return;
                        int count = get();
                        if (count < 1) throw new IOException("페이지가 없는 PDF입니다.");
                        selectedPdf = chosen;
                        explicitlyChosenPdf = chosen;
                        selectedPageCount = count;
                        firstPage.setModel(new SpinnerNumberModel(1, 1, count, 1));
                        lastPage.setModel(new SpinnerNumberModel(Math.min(count, 50), 1, count, 1));
                        pageCountLabel.setText("PDF 전체 " + count + "페이지 (한 번에 최대 50페이지)");
                        mainStatus.setText("선택한 PDF: " + chosen.getFileName() + "\n처리할 시작·종료 페이지를 선택하세요.");
                        cards.show(root, "main");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        showError("PDF 확인이 중단됐습니다.", e);
                    } catch (ExecutionException | IOException e) {
                        showError("PDF 페이지 수를 확인할 수 없습니다.", e);
                    } finally {
                        pageWorker = null;
                        setBusy(false);
                    }
                }
            };
            pageWorker.execute();
        } catch (IOException e) {
            showError("PDF를 선택할 수 없습니다.", e);
        }
    }

    private void generate() {
        if (selectedPdf == null || busy && generationWorker == null) return;
        Path pdf = selectedPdf;
        final Path chosen = pdf.equals(explicitlyChosenPdf) ? explicitlyChosenPdf : null;
        try {
            firstPage.commitEdit();
            lastPage.commitEdit();
        } catch (java.text.ParseException e) {
            showError("페이지 번호를 숫자로 입력하세요.", e);
            return;
        }
        int start = (Integer) firstPage.getValue();
        int end = (Integer) lastPage.getValue();
        if (start < 1 || end < start || end > selectedPageCount || end - start + 1 > 50) {
            showError("PDF 페이지 범위가 잘못되었습니다.", new IllegalArgumentException(
                    "1~" + selectedPageCount + "페이지에서 연속된 최대 50페이지를 선택하세요."));
            return;
        }
        final GenerationOptions options;
        try { options = pdfOptions.options(); }
        catch (IllegalArgumentException e) { showError("생성 옵션을 확인하세요.", e); return; }
        final TermExtractor.Mode mode = (TermExtractor.Mode) extractionMode.getSelectedItem();
        invalidateGeneration();
        final int token = generationVersion;
        setBusy(true);
        mainStatus.setText("PDF를 읽고 문제를 생성하는 중...");
        generationWorker = new SwingWorker<QuizWorkflow.Preview, Void>() {
            @Override protected QuizWorkflow.Preview doInBackground() throws Exception {
                return pdfInput.generate(pdf, start, end, options, mode);
            }
            @Override protected void done() {
                if (token != generationVersion || generationWorker != this) return;
                try {
                    if (isCancelled() || !isDisplayable()) return;
                    QuizWorkflow.Preview generated = get();
                    if (chosen != null) sourceView.rememberGenerated(generated.set(), chosen);
                    showPreview(generated);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("문제 생성이 중단됐습니다.", e);
                } catch (ExecutionException e) {
                    int option = JOptionPane.showConfirmDialog(QuizMakerFrame.this,
                            errorMessage("PDF에서 문제를 생성할 수 없습니다. 수동 입력으로 이동하시겠습니까?",
                                    e.getCause()), "PDF 처리 오류", JOptionPane.YES_NO_OPTION);
                    if (option == JOptionPane.YES_OPTION) openManual();
                } finally {
                    generationWorker = null;
                    setBusy(false);
                }
            }
        };
        setBusy(true);
        generationWorker.execute();
    }

    private void invalidateGeneration() {
        generationVersion++;
        SwingWorker<QuizWorkflow.Preview, Void> old = generationWorker;
        generationWorker = null;
        if (old != null) old.cancel(true);
    }

    private void addReviewedQuestion() {
        addFromSource(null);
    }

    @Override public void dispose() {
        invalidateGeneration();
        pdfSelectionVersion++;
        if (pageWorker != null) pageWorker.cancel(true);
        sourceView.stop();
        super.dispose();
    }

    private void addFromSource(PdfPageService.Selection selection) {
        if (preview == null || busy) return;
        createQuestion(workflow, preview, selection, () -> {
            Object[] types = {"4지선다", "O/X"};
            int selected = JOptionPane.showOptionDialog(this, "추가할 문제 유형을 선택하세요.", "문제 추가",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, types, types[0]);
            return selected < 0 ? null : selected == 0
                    ? SavedQuestion.Type.MULTIPLE_CHOICE : SavedQuestion.Type.OX;
        }, editor -> JOptionPane.showConfirmDialog(this, editor, "문제 직접 작성",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE),
                error -> showError("질문·보기·정답·해설을 확인하세요.", error), this::acceptAddedQuestion);
    }

    private void acceptAddedQuestion(QuizWorkflow.Preview updated) {
        preview = updated;
        reviewModel.addElement(updated.set().questions().getLast());
        reviewList.setSelectedIndex(reviewModel.size() - 1);
        reviewList.ensureIndexIsVisible(reviewModel.size() - 1);
        updateReviewCount();
    }

    /** 확인과 검증이 끝난 경우에만 화면 상태를 반영한다. 취소와 오류는 입력 중인 폼만 유지한다. */
    static void createQuestion(QuizWorkflow workflow, QuizWorkflow.Preview preview,
            Supplier<SavedQuestion.Type> chooseType, ToIntFunction<QuestionEditorPanel> showEditor,
            Consumer<IllegalArgumentException> showError, Consumer<QuizWorkflow.Preview> onAdded) {
        createQuestion(workflow, preview, null, chooseType, showEditor, showError, onAdded);
    }

    static void createQuestion(QuizWorkflow workflow, QuizWorkflow.Preview preview, PdfPageService.Selection selection,
            Supplier<SavedQuestion.Type> chooseType, ToIntFunction<QuestionEditorPanel> showEditor,
            Consumer<IllegalArgumentException> showError, Consumer<QuizWorkflow.Preview> onAdded) {
        SavedQuestion.Type type = chooseType.get();
        if (type == null) return;
        QuestionEditorPanel editor = new QuestionEditorPanel(type, preview.set().sourcePdf(), selection);
        while (showEditor.applyAsInt(editor) == JOptionPane.OK_OPTION) {
            QuizWorkflow.Preview updated;
            try {
                updated = editor.addTo(workflow, preview);
            } catch (IllegalArgumentException error) {
                showError.accept(error);
                continue;
            }
            onAdded.accept(updated);
            return;
        }
    }

    private void editReviewedQuestion() {
        if (busy) return;
        SavedQuestion selected = reviewList.getSelectedValue();
        if (selected == null || preview == null) return;
        QuestionEditorPanel editor = new QuestionEditorPanel(selected);
        while (JOptionPane.showConfirmDialog(this, editor, "문제 편집 — 원본 자료와 대조하세요",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                SavedQuestion edited = editor.editedQuestion();
                preview = workflow.editQuestion(preview, selected.id(), edited.question(),
                        edited.options(), edited.correctIndex(), edited.explanation());
                reviewModel.set(reviewList.getSelectedIndex(), edited);
                showReviewDetails();
                return;
            } catch (IllegalArgumentException e) {
                showError("질문·선택지·정답·해설을 확인하세요.", e);
            }
        }
    }

    private void showReviewDetails() {
        SavedQuestion question = reviewList.getSelectedValue();
        sourceView.showQuestion(preview == null ? null : preview.set(), question);
        if (question == null) {
            reviewDetails.setText("");
            excludedBox.setEnabled(false);
            return;
        }
        excludedBox.setEnabled(true);
        excludedBox.setSelected(excluded.contains(question.id()));
        StringBuilder text = new StringBuilder(question.question()).append("\n\n");
        for (int i = 0; i < question.options().size(); i++)
            text.append(i + 1).append(". ").append(question.options().get(i)).append("\n");
        text.append("\n정답: ").append(question.type() == SavedQuestion.Type.OX
                ? question.options().get(question.correctIndex()) : question.correctIndex() + 1);
        text.append("\n해설: ").append(question.explanation());
        text.append("\n출처: ").append(QuizMetadata.portableSource(question.sourcePdf()))
                .append(question.page() == null ? "" : " · " + question.page() + "쪽")
                .append(question.lineNumber() == null ? "" : " · " + question.lineNumber() + "행");
        reviewDetails.setText(text.toString());
        reviewDetails.setCaretPosition(0);
    }

    private void updateReviewCount() {
        if (preview == null) return;
        long ox = preview.set().questions().stream().filter(q ->
                q.type() == SavedQuestion.Type.OX && !excluded.contains(q.id())).count();
        long multiple = preview.set().questions().stream().filter(q ->
                q.type() == SavedQuestion.Type.MULTIPLE_CHOICE && !excluded.contains(q.id())).count();
        reviewCount.setText("출제 예정: O/X " + ox + "개 · 4지선다 " + multiple
                + "개 · 제외 " + excluded.size() + "개");
    }

    private void saveReviewed() {
        if (preview == null || busy) return;
        try {
            boolean confirmedUnchanged = false;
            if (preview.set().questions().stream().allMatch(q -> excluded.contains(q.id())))
                throw new IllegalArgumentException("저장할 문항이 없습니다. 최소 1개 문항을 남겨 주세요.");
            if (revisionOriginal != null) {
                Set<String> excludedIds = Set.copyOf(excluded);
                if (!workflow.hasReviewChanges(revisionOriginal, preview, excludedIds)) {
                    Object[] choices = {"복제본 저장", "취소"};
                    int choice = JOptionPane.showOptionDialog(this,
                            "변경 사항이 없습니다. 같은 내용의 복제본을 새 세트로 저장할까요?",
                            "변경 없는 수정본 저장", JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE, null, choices, choices[1]);
                    if (choice != JOptionPane.YES_OPTION) return;
                    confirmedUnchanged = true;
                }
            }
            boolean confirmed = confirmedUnchanged;
            readCatalog(listing -> {
                String title = revisionOriginal == null ? preview.set().title()
                        : QuizMetadata.fallback(revisionOriginal.title() + " (수정본)");
                QuizMetadataPanel panel = new QuizMetadataPanel(title, preview.set().subject(), listing.entries().stream()
                        .map(e -> e.set().subject()).filter(s -> !s.isEmpty()).distinct().sorted().toList());
                QuizMetadataPanel.Values values = panel.request(editor -> JOptionPane.showConfirmDialog(this,
                        editor, "문제 세트 이름과 과목", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE),
                        error -> showError("이름과 과목을 확인하세요.", error));
                if (values == null) return;
                try { persistReviewed(values, confirmed); }
                catch (IOException | IllegalArgumentException e) { showError("문제 세트를 저장할 수 없습니다.", e); }
            });
        } catch (IllegalArgumentException e) {
            showError("문제 세트를 저장할 수 없습니다.", e);
        }
    }

    private void persistReviewed(QuizMetadataPanel.Values values, boolean confirmed) throws IOException {
        String message;
        if (revisionOriginal != null) {
            QuizWorkflow.SavedRevision saved = workflow.saveRevision(revisionOriginal, preview,
                    Set.copyOf(excluded), confirmed, values.title(), values.subject());
            sourceView.inheritChosenConnection(revisionOriginal, saved.set());
            currentSet = saved.set();
            message = "수정본이 새 세트로 저장되었습니다. 원본과 기존 결과는 그대로 유지됩니다."
                    + "\n저장 경로: " + saved.path();
        } else {
            currentSet = workflow.saveReviewed(preview, Set.copyOf(excluded), values.title(), values.subject());
            message = "문제 세트 저장 완료: data/quizzes/" + currentSet.id() + ".json";
        }
        updateMainCount();
        mainStatus.setText(message);
        cards.show(root, "main");
        JOptionPane.showMessageDialog(this, message, "저장 완료", JOptionPane.INFORMATION_MESSAGE);
    }

    private void readCatalog(Consumer<QuizRepository.Listing> onLoaded) {
        setBusy(true);
        catalogWorker = new SwingWorker<>() {
            @Override protected QuizRepository.Listing doInBackground() throws IOException { return workflow.listQuizzes(); }
            @Override protected void done() {
                try {
                    if (isCancelled() || !isDisplayable()) return;
                    QuizRepository.Listing listing = get();
                    setBusy(false);
                    onLoaded.accept(listing);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) { showError("문제 세트 목록을 읽을 수 없습니다.", e); }
                finally { catalogWorker = null; setBusy(false); }
            }
        };
        catalogWorker.execute();
    }

    private void loadQuiz() {
        if (busy) return;
        library.loading();
        cards.show(root, "library");
        readCatalog(library::display);
    }

    private void loadListedQuiz(Path file) {
        if (busy) return;
        setBusy(true);
        loadWorker = new SwingWorker<>() {
            @Override protected SavedQuizSet doInBackground() throws IOException { return workflow.loadQuiz(file); }
            @Override protected void done() {
                try {
                    if (isCancelled() || !isDisplayable()) return;
                    currentSet = get();
                    updateMainCount();
                    showPreview(new QuizWorkflow.Preview(currentSet, java.util.List.of(),
                            "저장된 문제 세트: " + currentSet.title() + "\n" + file
                            + "\n편집·추가·제외 후 수정본으로 저장할 수 있습니다. 원본과 기존 결과는 유지됩니다."), currentSet);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) { showError("문제 세트를 불러올 수 없습니다.", e); }
                finally { loadWorker = null; setBusy(false); }
            }
        };
        loadWorker.execute();
    }

    private void exportListedQuiz(QuizRepository.Entry entry) {
        if (busy) return;
        Path target = transferDialogs.exportTarget(this, QuizMetadata.exportFileName(entry.set().title()));
        if (target == null) return;
        transfer(() -> workflow.exportQuiz(entry.path(), target),
                path -> transferDialogs.info(this, "퀴즈 세트를 내보냈습니다.\n" + path),
                error -> transferDialogs.error(this, error instanceof java.nio.file.FileAlreadyExistsException
                        ? "같은 이름의 파일이 있습니다. 다른 이름을 선택하세요." : "내보내기에 실패했습니다.", error));
    }

    private void importListedQuiz() {
        if (busy) return;
        Path source = transferDialogs.importSource(this);
        if (source == null) return;
        transfer(() -> workflow.inspectImport(source), this::confirmImport,
                error -> transferDialogs.error(this, "가져올 파일을 읽을 수 없습니다.", error));
    }

    private void confirmImport(QuizTransfer.ImportPlan plan) {
        boolean asCopy = plan.hasConflict();
        if (asCopy && !transferDialogs.copyConflict(this, plan)) return;
        transfer(() -> workflow.importQuiz(plan, asCopy), imported -> {
            transferDialogs.info(this, "퀴즈 세트를 가져왔습니다.\n" + imported.path()
                    + (imported.warning().isEmpty() ? "" : "\n" + imported.warning()));
            library.loading();
            cards.show(root, "library");
            readCatalog(listing -> { library.display(listing); library.selectSet(imported.set().id()); });
        }, error -> {
            if (error instanceof QuizTransfer.ConflictException conflict) confirmImport(conflict.plan());
            else transferDialogs.error(this, "가져오기에 실패했습니다.", error);
        });
    }

    private <T> void transfer(java.util.concurrent.Callable<T> action, Consumer<T> completed, Consumer<Throwable> failed) {
        setBusy(true); library.setWorking(true);
        transferWorker = new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return action.call(); }
            @Override protected void done() {
                T value = null;
                Throwable failure = null;
                try {
                    if (isCancelled() || !isDisplayable()) return;
                    value = get();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); failure = e; }
                catch (ExecutionException e) { failure = e.getCause(); }
                finally { transferWorker = null; setBusy(false); library.setWorking(false); }
                if (failure == null) completed.accept(value);
                else failed.accept(failure);
            }
        };
        transferWorker.execute();
    }

    private void loadReview() {
        if (busy) return;
        Path quizFile = chooseFile(Path.of("data", "quizzes"));
        if (quizFile == null) return;
        Path resultFile = chooseFile(Path.of("data", "results"));
        if (resultFile == null) return;
        try {
            QuizWorkflow.Review review = workflow.loadReview(quizFile, resultFile);
            if (review.wrong().isEmpty()) {
                JOptionPane.showMessageDialog(this, "복습할 오답이 없습니다.");
                return;
            }
            currentSet = review.set();
            session = workflow.startReview(review);
            renderQuestion();
            cards.show(root, "quiz");
        } catch (IOException e) {
            showError("오답 기록을 불러올 수 없습니다.", e);
        }
    }

    private void beginQuiz() {
        if (busy) return;
        if (currentSet == null || currentSet.questions().isEmpty()) {
            JOptionPane.showMessageDialog(this, "풀 수 있는 문제가 없습니다.");
            return;
        }
        session = workflow.start(currentSet);
        renderQuestion();
        cards.show(root, "quiz");
    }

    private void renderQuestion() {
        SavedQuestion question = session.current();
        updateAttemptText();
        quizProgress.setMaximum(session.total());
        updateQuizProgress();
        nextButton.setText(session.index() + 1 == session.total() ? "결과 보기" : "다음 문제");
        int descriptionStart = question.type() == SavedQuestion.Type.OX
                ? question.question().indexOf("\n\n설명: ") : -1;
        questionText.setText(descriptionStart < 0 ? question.question()
                : question.question().substring(0, descriptionStart));
        oxDescription.setText(descriptionStart < 0 ? ""
                : question.question().substring(descriptionStart + 2));
        oxDescriptionScroll.setVisible(descriptionStart >= 0);
        questionText.setCaretPosition(0);
        choices.removeAll();
        choiceGroup = new ButtonGroup();
        String[] labels = {"①", "②", "③", "④"};
        for (int i = 0; i < question.options().size(); i++) {
            String answer = question.type() == SavedQuestion.Type.OX
                    ? question.options().get(i) : String.valueOf(i + 1);
            JRadioButton button = new JRadioButton(
                    question.type() == SavedQuestion.Type.OX ? answer : labels[i]);
            button.setActionCommand(answer);
            button.addActionListener(event -> submitAnswer());
            choiceGroup.add(button);
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.add(button, BorderLayout.WEST);
            JTextArea option = optionText(question.options().get(i));
            row.add(option, BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    option.getPreferredSize().height + 12));
            choices.add(row);
        }
        choices.revalidate();
        choices.repaint();
        feedback.setText("출처: " + source(question));
        nextButton.setEnabled(false);
    }

    private void submitAnswer() {
        if (session == null || session.submitted()) return;
        if (choiceGroup.getSelection() == null) {
            JOptionPane.showMessageDialog(this, "답안을 선택하세요.");
            return;
        }
        SavedQuestion question = session.current();
        try {
            AnswerRecord answer = session.submit(choiceGroup.getSelection().getActionCommand());
            feedback.setText(answerFeedback(question, answer));
            updateAttemptText();
            if (answer.correct()) {
                nextButton.setEnabled(true);
                for (java.util.Enumeration<javax.swing.AbstractButton> buttons = choiceGroup.getElements();
                        buttons.hasMoreElements();) buttons.nextElement().setEnabled(false);
            } else {
                choiceGroup.clearSelection();
            }
            updateQuizProgress();
        } catch (IllegalArgumentException | IllegalStateException e) {
            showError("답안을 제출할 수 없습니다.", e);
        }
    }

    private void nextQuestion() {
        if (session == null || !session.solved()) return;
        session.next();
        if (session.isComplete()) finishQuiz();
        else renderQuestion();
    }

    private void finishQuiz() {
        if (session == null) return;
        QuizSession completed = session;
        Path file = null;
        try {
            file = workflow.saveResult(completed);
        } catch (IOException e) {
            nextButton.setEnabled(false);
            endButton.setText("결과 저장 다시 시도");
            if (notebookSession) notebookDialogs.saveError(this, e);
            else showError("학습 결과를 저장할 수 없습니다. 다시 시도할 수 있습니다.", e);
            return;
        }
        endButton.setText("중간 종료 및 결과 저장");
        lastResult = completed.finish();
        currentSet = completed.set();
        session = null;
        showResult(lastResult, currentSet, file);
        if (notebookSession) {
            refreshNotebook(false);
            if (notebookQueue.remaining() > 0 && notebookDialogs.continueWithNext(this, notebookQueue.remaining())) beginNotebookBatch();
            else { notebookQueue.clear(); notebookSession = false; }
        }
    }

    private void openNotebook() {
        notebookQueue.clear(); notebookSession = false;
        refreshNotebook(true);
    }

    private void refreshNotebook(boolean show) {
        int generation = ++notebookGeneration;
        if (notebookWorker != null) notebookWorker.cancel(true);
        notebookPanel.loading();
        if (show) cards.show(root, "notebook");
        notebookWorker = new SwingWorker<>() {
            @Override protected com.quiz.WrongAnswerNotebook.Snapshot doInBackground() throws Exception { return workflow.notebook(); }
            @Override protected void done() {
                if (generation != notebookGeneration) return;
                try {
                    if (!isCancelled() && isDisplayable()) notebookPanel.showSnapshot(get());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); notebookPanel.failed(e); }
                catch (ExecutionException e) { notebookPanel.failed(e.getCause()); }
                finally { notebookWorker = null; }
            }
        };
        notebookWorker.execute();
    }

    private void startNotebook(java.util.List<com.quiz.WrongAnswerNotebook.Group> selected, String setId) {
        notebookQueue.start(selected, setId);
        if (notebookQueue.remaining() == 0) { notebookSession = false; return; }
        notebookSession = true; beginNotebookBatch();
    }

    private void beginNotebookBatch() {
        NotebookQueue.Batch batch = notebookQueue.next();
        if (batch == null) { notebookSession = false; return; }
        currentSet = batch.set(); session = workflow.startNotebook(batch.set(), batch.questions());
        updateMainCount();
        nextButton.setEnabled(true); endButton.setText("중간 종료 및 결과 저장"); renderQuestion(); cards.show(root, "quiz");
    }

    private void showNotebookHome() {
        notebookQueue.clear(); notebookSession = false; notebookGeneration++;
        if (notebookWorker != null) notebookWorker.cancel(true);
        notebookWorker = null; cards.show(root, "main");
    }

    private void showResult(QuizResult result, SavedQuizSet set, Path file) {
        QuizWorkflow.Summary summary = workflow.summary(result);
        resultDetails.setText(String.format(java.util.Locale.ROOT,
                "전체 %d문제\n최초 시도 정답 %d · 최초 시도 오답 %d · 미응답 %d\n"
                + "재시도로 해결 %d · 아직 미해결 %d\n최초 시도 정답률 %.1f%%\n"
                + "최초 시도 응답률 %.1f%%\n\n문제 세트: %s\n학습 결과: %s",
                summary.total(), summary.correct(), summary.wrong(), summary.unanswered(),
                result.retriedSolvedCount(), result.unresolvedAttemptedCount(),
                summary.accuracy(), summary.completion(),
                set.id(), file == null ? "저장 실패" : file));
        cards.show(root, "result");
    }

    private void reviewLastResult() {
        notebookQueue.clear(); notebookSession = false;
        if (lastResult == null || currentSet == null) return;
        java.util.List<SavedQuestion> wrong = lastResult.wrongQuestionsRecentFirst(currentSet);
        if (wrong.isEmpty()) {
            JOptionPane.showMessageDialog(this, "복습할 오답이 없습니다.");
            return;
        }
        session = workflow.startReview(new QuizWorkflow.Review(currentSet, lastResult, wrong));
        renderQuestion();
        cards.show(root, "quiz");
    }

    private void updateMainCount() {
        long ox = currentSet.questions().stream().filter(q -> q.type() == SavedQuestion.Type.OX).count();
        counts.setText("O/X " + ox + "개 · 4지선다 " + (currentSet.questions().size() - ox) + "개");
        startButton.setEnabled(!currentSet.questions().isEmpty());
    }

    private Path chooseFile(Path directory) {
        JFileChooser chooser = new JFileChooser(directory == null ? null : directory.toFile());
        int selected = chooser.showOpenDialog(this);
        if (selected != JFileChooser.APPROVE_OPTION) {
            mainStatus.setText("파일 선택을 취소했습니다.");
            return null;
        }
        return chooser.getSelectedFile().toPath();
    }

    private void setBusy(boolean value) {
        pdfOptions.setEnabled(!value);
        extractionMode.setEnabled(!value);
        manualOptions.setEnabled(!value);
        busy = value;
        boolean canSupersede = generationWorker != null && pageWorker == null;
        choosePdfButton.setEnabled(!value || canSupersede);
        manualButton.setEnabled(!value);
        loadQuizButton.setEnabled(!value);
        loadReviewButton.setEnabled(!value);
        notebookButton.setEnabled(!value);
        generateButton.setEnabled((!value || canSupersede) && selectedPdf != null);
        startButton.setEnabled(!value && currentSet != null && !currentSet.questions().isEmpty());
    }

    private static String source(SavedQuestion question) {
        return question.sourcePdf() + (question.page() == null ? ""
                : " (페이지 " + question.page()
                + (question.lineNumber() == null ? "" : ", 줄 " + question.lineNumber()) + ")");
    }

    static String answerFeedback(SavedQuestion question, AnswerRecord answer) {
        if (!answer.correct()) return "오답입니다! 다시 선택하세요!";
        return "정답입니다!\n해설: " + question.explanation() + "\n출처: " + source(question);
    }

    private void updateAttemptText() {
        quizProgressText.setText("문제 " + (session.index() + 1) + " / " + session.total()
                + " · 재시도 " + session.retries() + "회");
    }

    private void updateQuizProgress() {
        int completed = session.index() + (session.solved() ? 1 : 0);
        quizProgress.setValue(completed);
        quizProgress.setString(completed + " / " + session.total());
    }

    private void showError(String message, Throwable cause) {
        JOptionPane.showMessageDialog(this, errorMessage(message, cause),
                "오류", JOptionPane.ERROR_MESSAGE);
    }

    static String errorMessage(String message, Throwable cause) {
        if (cause == null) return message;
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String detail = root.getMessage();
        return message + "\n원인: " + root.getClass().getName()
                + (detail == null || detail.isBlank() ? "" : " - " + detail);
    }

    static JTextArea reviewCell(String text, int width) {
        JTextArea area = textArea();
        area.setText(text == null ? "" : text);
        area.setFont(javax.swing.UIManager.getFont("List.font"));
        area.setSize(Math.max(240, width - 28), Short.MAX_VALUE);
        area.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        return area;
    }

    static JTextArea optionText(String text) {
        JTextArea area = textArea();
        String shown = text == null || text.isBlank() ? "설명 없음" : text;
        area.setText(shown);
        area.setColumns(28);
        area.setRows(Math.max(2, (shown.codePointCount(0, shown.length()) + 27) / 28));
        return area;
    }

    private static JTextArea textArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static JPanel page() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        return panel;
    }
}
