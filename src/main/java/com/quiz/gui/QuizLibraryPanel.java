package com.quiz.gui;

import com.quiz.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

final class QuizLibraryPanel extends JPanel {
    final JLabel status = new JLabel();
    final JTable table = new JTable();
    final JButton open = new JButton("선택한 세트 열기");
    final JButton export = new JButton("내보내기");
    final JButton importFile = new JButton("가져오기");
    private boolean working;
    private final JTextArea reasons = new JTextArea();
    private final JScrollPane reasonScroll = new JScrollPane(reasons);
    private List<QuizRepository.Entry> entries = List.of();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.of("Asia/Seoul"));

    QuizLibraryPanel(Consumer<Path> onOpen, Runnable onBack) {
        this(onOpen, onBack, entry -> { }, () -> { });
    }

    QuizLibraryPanel(Consumer<Path> onOpen, Runnable onBack, Consumer<QuizRepository.Entry> onExport, Runnable onImport) {
        super(new BorderLayout(8, 8));
        add(status, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateActions());
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        open.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) onOpen.accept(entries.get(table.convertRowIndexToModel(row)).path());
        });
        export.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) onExport.accept(entries.get(table.convertRowIndexToModel(row)));
        });
        importFile.addActionListener(e -> onImport.run());
        JButton back = new JButton("메인으로");
        back.addActionListener(e -> onBack.run());
        actions.add(open); actions.add(export); actions.add(importFile); actions.add(back); add(actions, BorderLayout.SOUTH);
        reasons.setEditable(false); reasons.setLineWrap(true); reasons.setWrapStyleWord(true);
        reasons.setColumns(24); add(reasonScroll, BorderLayout.EAST);
        loading();
    }

    void loading() {
        entries = List.of();
        table.setModel(new DefaultTableModel());
        open.setEnabled(false);
        setWorking(true);
        reasons.setText(""); reasonScroll.setVisible(false);
        status.setText("문제 세트 목록을 읽는 중...");
    }

    void display(QuizRepository.Listing listing) {
        entries = listing.entries();
        DefaultTableModel model = new DefaultTableModel(new String[] {"이름", "과목", "문항 수", "생성일", "원본 PDF"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (QuizRepository.Entry entry : entries) {
            SavedQuizSet set = entry.set();
            long ox = set.questions().stream().filter(q -> q.type() == SavedQuestion.Type.OX).count();
            model.addRow(new Object[] {set.title(), set.subject(), "4지선다 " + (set.questions().size() - ox)
                    + " / OX " + ox, date(set.createdAt()), QuizMetadata.fileName(set.sourcePdf())});
        }
        table.setModel(model);
        setWorking(false);
        status.setText("문제 세트 " + entries.size() + "개 · 읽을 수 없는 파일 " + listing.unreadableCount() + "개");
        // 파일 경로와 오류는 HTML로 해석하지 않고 별도 텍스트 영역에 표시한다.
        reasons.setText(String.join("\n", listing.reasons()));
        reasonScroll.setVisible(!listing.reasons().isEmpty());
        revalidate(); repaint();
    }

    void selectSet(String id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).set().id().equals(id)) {
            int row = table.convertRowIndexToView(i);
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
            return;
        }
    }

    void setWorking(boolean value) { working = value; updateActions(); }
    private void updateActions() {
        boolean selected = table.getSelectedRow() >= 0 && !working;
        open.setEnabled(selected); export.setEnabled(selected); importFile.setEnabled(!working);
    }

    private static String date(java.time.Instant at) {
        try { return DATE.format(at); }
        catch (java.time.DateTimeException e) { return at.toString(); }
    }
}
