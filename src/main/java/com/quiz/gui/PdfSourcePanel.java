package com.quiz.gui;

import com.quiz.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import javax.swing.*;
import javax.swing.text.DefaultHighlighter;

/** 원문 텍스트 표시와 선택만 담당한다. 작업 번호로 늦게 도착한 이전 읽기를 무시한다. */
final class PdfSourcePanel extends JPanel {
    final JTextArea text = new JTextArea(), numbers = new JTextArea();
    final JLabel file = new JLabel("PDF: —"), status = new JLabel("원문 정보가 없습니다");
    final JButton find = new JButton("PDF 다시 찾기"), add = new JButton("선택한 텍스트로 문제 추가");
    final JScrollPane scroll = new JScrollPane(text);
    private final PdfPageService service;
    private final Consumer<PdfPageService.Selection> onSelected;
    private final Supplier<Path> chooser;
    private final Consumer<String> error;
    private SavedQuizSet set;
    private SavedQuestion question;
    private PdfPageService.PageText page;
    private Integer highlightedStart;
    private int generation;
    SwingWorker<Loaded, Void> worker;
    private record Loaded(PdfPageService.Resolved source, PdfPageService.PageText page) { }

    void rememberGenerated(SavedQuizSet set, Path explicitlyChosen) {
        if (set.sourcePdf().equals(explicitlyChosen.toAbsolutePath().normalize().toString()))
            service.remember(set.id(), set.sourcePdf(), explicitlyChosen);
    }
    void inheritChosenConnection(SavedQuizSet original, SavedQuizSet revision) {
        service.inheritChosenConnection(original, revision);
    }

    PdfSourcePanel(PdfPageService service, Consumer<PdfPageService.Selection> onSelected) {
        this(service, onSelected, null, null);
    }
    PdfSourcePanel(PdfPageService service, Consumer<PdfPageService.Selection> onSelected, Supplier<Path> chooser, Consumer<String> error) {
        super(new BorderLayout(4,4)); this.service=service;this.onSelected=onSelected;
        this.chooser=chooser==null?()->choosePdf():chooser;
        this.error=error==null?s->JOptionPane.showMessageDialog(this,s,"원문 보기",JOptionPane.WARNING_MESSAGE):error;
        text.setEditable(false);numbers.setEditable(false);numbers.setFocusable(false);
        text.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));numbers.setFont(text.getFont());
        numbers.setBackground(UIManager.getColor("Panel.background"));numbers.setColumns(5);
        scroll.setRowHeaderView(numbers);
        JPanel header=new JPanel(new BorderLayout());header.add(file,BorderLayout.CENTER);header.add(find,BorderLayout.EAST);
        add(header,BorderLayout.NORTH);add(scroll,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout());bottom.add(status,BorderLayout.CENTER);bottom.add(add,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        add.setEnabled(false);find.setEnabled(false);
        text.addCaretListener(e->selectionChanged());
        addHierarchyListener(e->{if((e.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0&&isShowing())SwingUtilities.invokeLater(this::scrollToHighlight);});
        find.addActionListener(e->{if(question==null||question.page()==null)return;Path chosen=this.chooser.get();if(chosen!=null)read(chosen);});
        add.addActionListener(e->{try{onSelected.accept(selection());}catch(IllegalArgumentException ex){this.error.accept(ex.getMessage());}});
    }
    private Path choosePdf() {
        JFileChooser dialog=new JFileChooser();dialog.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF 파일","pdf"));
        return dialog.showOpenDialog(this)==JFileChooser.APPROVE_OPTION?dialog.getSelectedFile().toPath():null;
    }
    void showQuestion(SavedQuizSet set,SavedQuestion question) {
        stop();this.set=set;this.question=question;page=null;highlightedStart=null;text.setText("");numbers.setText("");text.getHighlighter().removeAllHighlights();
        file.setText("PDF: —");find.setEnabled(question!=null&&question.page()!=null);add.setEnabled(false);
        if(question==null||question.page()==null){status.setText("원문 정보가 없습니다");return;}
        read(null);
    }
    private void read(Path chosen) {
        stop();final int token=generation;SavedQuestion captured=question;SavedQuizSet capturedSet=set;
        status.setText("원문 읽는 중…");add.setEnabled(false);
        worker=new SwingWorker<>() {
            protected Loaded doInBackground() throws Exception {
                if(chosen!=null) {
                    var loaded=service.validateChoice(chosen,captured.page());
                    return new Loaded(new PdfPageService.Resolved(chosen,true),loaded);
                }
                var resolved=service.resolve(capturedSet.id(),capturedSet.sourcePdf());
                return resolved==null?null:new Loaded(resolved,service.load(resolved.path(),captured.page()));
            }
            protected void done() {
                if(token!=generation)return;
                worker=null;
                try {
                    Loaded loaded=get();
                    if(loaded==null){status.setText("PDF 찾기로 원본 PDF를 선택하세요.");return;}
                    if(chosen!=null)service.remember(capturedSet.id(),capturedSet.sourcePdf(),chosen);
                    page=loaded.page();text.setText(page.text());
                    StringBuilder lines=new StringBuilder();for(int i=1;i<=page.starts().size();i++){if(i>1)lines.append('\n');lines.append(i);}
                    numbers.setText(lines.toString());
                    file.setText("PDF: "+loaded.source().path().getFileName()+(loaded.source().chosen()?" (사용자가 선택한 PDF)":" (자동 연결)"));
                    text.getHighlighter().removeAllHighlights();highlightedStart=null;text.setCaretPosition(0);
                    String message=page.truncated()?"20,000자 표시 상한으로 원문이 잘렸습니다.":"원문을 선택해 문제를 작성할 수 있습니다.";
                    Integer line=captured.lineNumber();
                    if(line!=null&&line<=page.starts().size()) {
                        int start=page.starts().get(line-1),end=start+page.line(line).length();
                        text.getHighlighter().addHighlight(start,end,new DefaultHighlighter.DefaultHighlightPainter(new Color(255,240,150)));
                        highlightedStart=start;
                        text.setCaretPosition(start);
                        SwingUtilities.invokeLater(()->{if(token==generation)scrollToHighlight();});
                    } else if(line!=null)message+=" 해당 줄은 표시 범위 밖입니다.";
                    status.setText(message);selectionChanged();
                } catch(CancellationException ignored) { }
                catch(Exception ex) {
                    status.setText("PDF를 읽을 수 없습니다. 파일·페이지·암호화/손상 여부를 확인하고 PDF 다시 찾기를 사용하세요.");
                    if(chosen!=null)error.accept("선택한 PDF를 열 수 없거나 해당 페이지가 없습니다.");
                    selectionChanged();
                }
            }
        };
        worker.execute();
    }
    private PdfPageService.Selection selection() {
        if(page==null||question==null)throw new IllegalArgumentException("원문 텍스트를 선택하세요.");
        return PdfPageService.prepare(page,question.page(),text.getSelectionStart(),text.getSelectionEnd());
    }
    private void selectionChanged() {
        add.setEnabled(false);if(worker!=null||page==null)return;
        if(text.getSelectionStart()==text.getSelectionEnd())return;
        try {selection();add.setEnabled(true);}
        catch(IllegalArgumentException e){status.setText(e.getMessage());}
    }
    private void scrollToHighlight() {
        if(highlightedStart==null)return;
        try{var rectangle=text.modelToView2D(highlightedStart);if(rectangle!=null)text.scrollRectToVisible(rectangle.getBounds());}
        catch(javax.swing.text.BadLocationException ignored){}
    }
    void stop() {generation++;if(worker!=null){worker.cancel(true);worker=null;}}
}
