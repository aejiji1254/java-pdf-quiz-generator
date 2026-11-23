package com.quiz.gui;

import com.quiz.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

final class WrongAnswerNotebookPanel extends JPanel {
    record Filter(String label, String value) { @Override public String toString() { return label; } }
    final JComboBox<Filter> subject = new JComboBox<>(), set = new JComboBox<>();
    final JCheckBox includeMastered = new JCheckBox("정복 포함");
    final JSpinner threshold = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
    final JComboBox<String> order = new JComboBox<>(new String[] {"최근 오답 순", "많이 틀린 순"});
    final JButton start = new JButton("오답노트로 퀴즈 시작");
    final JLabel status = new JLabel("아직 오답이 없습니다");
    final JTextArea warnings = new JTextArea(3, 60);
    final DefaultTableModel model = new DefaultTableModel(new String[] {"질문", "세트", "과목", "PDF", "페이지", "시도", "오답", "마지막 풀이", "정복"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    final JTable table = new JTable(model) {
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            return row < 0 || row >= visible.size() ? null : visible.get(row).instances().stream()
                    .map(i -> i.set().title() + " [" + i.set().subject() + "]").distinct().collect(java.util.stream.Collectors.joining(" / "));
        }
    };
    private WrongAnswerNotebook.Snapshot snapshot;
    private List<WrongAnswerNotebook.Group> visible = List.of();
    private boolean updating, loading;

    WrongAnswerNotebookPanel(BiConsumer<List<WrongAnswerNotebook.Group>, String> onStart, Runnable back) {
        super(new BorderLayout(8, 8));
        JPanel filters = new JPanel(new java.awt.GridLayout(2, 1));
        JPanel sources = new JPanel(new FlowLayout(FlowLayout.LEFT)), options = new JPanel(new FlowLayout(FlowLayout.LEFT));
        subject.setPreferredSize(new java.awt.Dimension(160, 26)); set.setPreferredSize(new java.awt.Dimension(260, 26));
        sources.add(subject); sources.add(set); options.add(includeMastered);
        options.add(new JLabel("정복 연속 정답")); options.add(threshold); options.add(order);
        filters.add(sources); filters.add(options);
        add(filters, BorderLayout.NORTH); add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        warnings.setEditable(false); warnings.setLineWrap(true); warnings.setWrapStyleWord(true);
        footer.add(new JScrollPane(warnings), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(status); actions.add(start);
        JButton home = new JButton("메인으로"); home.addActionListener(e -> back.run()); actions.add(home);
        footer.add(actions, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        subject.addActionListener(e -> refresh()); set.addActionListener(e -> refresh());
        includeMastered.addActionListener(e -> refresh()); threshold.addChangeListener(e -> refresh()); order.addActionListener(e -> refresh());
        table.getSelectionModel().addListSelectionListener(e -> updateStart());
        start.addActionListener(e -> onStart.accept(Arrays.stream(table.getSelectedRows()).mapToObj(visible::get).toList(), selectedSet()));
        start.setEnabled(false);
    }
    String selectedSet() { Filter choice = (Filter) set.getSelectedItem(); return choice == null ? null : choice.value(); }
    void loading() {
        loading = true; visible = List.of(); model.setRowCount(0); status.setText("오답노트를 읽는 중..."); warnings.setText("");
        controls(false); updateStart();
    }
    void failed(Throwable error) { loading = false; status.setText("오답노트를 읽을 수 없습니다"); warnings.setText(error.getMessage()); controls(false); }
    void showSnapshot(WrongAnswerNotebook.Snapshot value) {
        String subjectValue = subject.getSelectedItem() == null ? null : ((Filter) subject.getSelectedItem()).value();
        String setValue = selectedSet(); updating = true; snapshot = value; loading = false;
        subject.removeAllItems(); set.removeAllItems(); subject.addItem(new Filter("전체 과목", null)); set.addItem(new Filter("전체 세트", null));
        Set<String> subjects = new TreeSet<>(); Map<String, SavedQuizSet> sets = new TreeMap<>();
        for (var group : value.groups()) for (var instance : group.instances()) { subjects.add(instance.set().subject()); sets.put(instance.set().id(), instance.set()); }
        for (String s : subjects) subject.addItem(new Filter(s.isEmpty() ? "(과목 없음)" : s, s));
        for (var s : sets.values()) set.addItem(new Filter(s.title() + " (" + s.id().substring(0, 8) + ")", s.id()));
        select(subject, subjectValue); select(set, setValue);
        StringBuilder text = new StringBuilder("제외된 결과 " + value.excludedResults() + "개");
        value.reasons().forEach(reason -> text.append("\n").append(reason));
        if (value.unreadableSets() > 0) { text.append("\n읽을 수 없는 세트 ").append(value.unreadableSets()).append("개");
            value.setReasons().forEach(reason -> text.append("\n").append(reason)); }
        warnings.setText(text.toString()); warnings.setCaretPosition(0); updating = false; controls(true); refresh();
    }
    private static void select(JComboBox<Filter> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) if (Objects.equals(combo.getItemAt(i).value(), value)) { combo.setSelectedIndex(i); return; }
    }
    private void controls(boolean enabled) {
        subject.setEnabled(enabled); set.setEnabled(enabled); includeMastered.setEnabled(enabled); threshold.setEnabled(enabled); order.setEnabled(enabled);
    }
    private void refresh() {
        if (updating || loading || snapshot == null) return;
        String selectedSubject = subject.getSelectedItem() == null ? null : ((Filter) subject.getSelectedItem()).value();
        int n = ((Number) threshold.getValue()).intValue();
        visible = snapshot.visible(selectedSubject, selectedSet(), includeMastered.isSelected(), n,
                order.getSelectedIndex() == 0 ? WrongAnswerNotebook.Order.RECENT_WRONG : WrongAnswerNotebook.Order.MOST_WRONG);
        model.setRowCount(0);
        for (var group : visible) {
            var instance = group.representative(selectedSet());
            model.addRow(new Object[] {WrongAnswerNotebook.summary(instance.question().question()), instance.set().title(), instance.set().subject(),
                    QuizMetadata.portableSource(instance.set().sourcePdf()), instance.question().page() == null ? "페이지 없음" : instance.question().page(),
                    group.attempts(), group.wrongCount(), group.lastAnswered().toString(), group.mastered(n) ? "정복" : "미정복"});
        }
        status.setText(snapshot.groups().isEmpty() ? "아직 오답이 없습니다" : visible.isEmpty() ? "조건에 맞는 오답이 없습니다" : visible.size() + "개 문항");
        updateStart();
    }
    private void updateStart() { start.setEnabled(!loading && !visible.isEmpty() && table.getSelectedRowCount() > 0); }
}
