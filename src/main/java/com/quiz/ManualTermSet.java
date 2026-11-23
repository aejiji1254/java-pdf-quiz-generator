package com.quiz;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 사용자가 확인해 입력한 용어. page=0은 PDF 페이지를 검증하지 않았음을 뜻한다. */
public record ManualTermSet(String id, String associatedPdf, List<Term> terms) {
    public ManualTermSet {
        if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)
                || associatedPdf == null || terms == null)
            throw new IllegalArgumentException("수동 용어 세트의 필수 값이 잘못되었습니다.");
        associatedPdf = associatedPdf.strip();
        terms = List.copyOf(terms);
        Set<String> names = new HashSet<>();
        for (Term item : terms) {
            if (item == null || item.term() == null || item.description() == null
                    || item.term().isBlank() || item.description().isBlank()
                    || item.page() != 0) throw new IllegalArgumentException("용어와 설명을 모두 입력하세요.");
            String name = item.term().strip();
            if (!names.add(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("중복 용어입니다: " + name);
        }
    }

    public static ManualTermSet empty(String associatedPdf) {
        return new ManualTermSet(UUID.randomUUID().toString(),
                associatedPdf == null ? "" : associatedPdf, List.of());
    }

    public ManualTermSet add(String term, String description) {
        if (term == null || description == null || term.isBlank() || description.isBlank())
            throw new IllegalArgumentException("용어와 설명을 모두 입력하세요.");
        List<Term> updated = new ArrayList<>(terms);
        updated.add(new Term(term.strip(), description.strip(), 0));
        return new ManualTermSet(UUID.randomUUID().toString(), associatedPdf, updated);
    }

    public ManualTermSet remove(int index) {
        List<Term> updated = new ArrayList<>(terms);
        updated.remove(index);
        return new ManualTermSet(UUID.randomUUID().toString(), associatedPdf, updated);
    }

    public String sourceLabel() {
        return "수동 입력" + (associatedPdf.isBlank() ? " (PDF 미지정)"
                : " (원문 대조 필요: " + associatedPdf + ")");
    }
}
