package com.quiz;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;
import static org.junit.Assert.*;

/** 기준 리소스를 읽기만 하는 일회성 영향 분석. 문항 설명/보기 인용도 각각 100 code point 이내다. */
public class BracketImpactAudit {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @SuppressWarnings("unchecked")
    @Test public void reportOnlyWhenRequested() throws Exception {
        if (!Boolean.getBoolean("quiz.writeDocs")) return;
        try (var support = new BracketRuleTestSupport(temporary.newFolder().toPath())) {
            List<Object> contexts = new ArrayList<>(), questions = new ArrayList<>();
            Map<String,Object> baseline = (Map<String,Object>) QuizJson.parse(Files.readString(McBaselineCapture.BASELINE));
            Map<String,Object> actual = McBaselineCapture.current(); // 재캡처/갱신이 아닌 기존 재생 메서드만 사용한다.
            Map<String,Object> structuredBaseline = (Map<String,Object>) QuizJson.parse(Files.readString(StructuredExtractionReview.STRUCTURED));
            var oldDocs = rows(baseline.get("documents")); var newDocs = rows(actual.get("documents"));
            for (int i=0;i<oldDocs.size();i++) {
                var a=oldDocs.get(i);var b=newDocs.get(i);
                assertEquals(a.get("key"),b.get("key")); assertEquals(a.get("sha256"), b.get("sha256"));
                var sample=OxReviewFixture.samples().get(i);
                Map<String,Term> unique = new LinkedHashMap<>();
                for(Term t:sample.extracted().terms())unique.putIfAbsent(t.term().strip().toLowerCase(Locale.ROOT), new Term(t.term(),t.description(),0));
                var manual=new TermExtractor.Result(new ArrayList<>(unique.values()),List.of());
                var plain=new TermExtractor.Result(sample.extracted().terms(),List.of());
                var aa=rows(a.get("cases"));var bb=rows(b.get("cases"));
                for(int j=0;j<aa.size();j++) {
                    String path=(String)aa.get(j).get("path"); assertEquals(path,bb.get(j).get("path"));
                    var input=path.equals("MANUAL")?manual:path.equals("MAIN_MENU_1")?plain:sample.extracted();
                    contexts.add(context(support,sample.pdf(),sample.first(),sample.last(),path,"LEGACY",input,sample.extracted()));
                    if(path.equals("MAIN_MENU_5")||path.equals("MAIN_INTERACTIVE_5"))
                        contexts.add(context(support,sample.pdf(),sample.first(),sample.last(),path+"/OX_LIST","LEGACY",plain,sample.extracted()));
                    questions.add(questionDiff(sample.pdf().getFileName().toString(),sample.first(),sample.last(),path,"LEGACY",
                            rows(aa.get(j).get("questions")), rows(bb.get(j).get("questions")),aa.get(j).get("nextRandomLongs"),bb.get(j).get("nextRandomLongs")));
                }
                var structured=new TermExtractor().extract(new PdfReader().readRange(sample.pdf(),sample.first(),sample.last()),sample.first(),TermExtractor.Mode.STRUCTURED);
                contexts.add(context(support,sample.pdf(),sample.first(),sample.last(),"PDF_MIXED30","STRUCTURED",structured,structured));
                var oldRun=support.replay(structured,false); var newRun=support.replay(structured,true);
                var frozenDoc=rows(structuredBaseline.get("documents")).stream().filter(d->d.get("pdf").equals(sample.pdf().getFileName().toString())).findFirst().orElseThrow();
                assertEquals(frozenDoc.get("sha256"),McBaselineCapture.sha(sample.pdf()));
                var frozenRange=rows(frozenDoc.get("ranges")).stream().filter(r->r.get("first").equals(sample.first())&&r.get("last").equals(sample.last())).findFirst().orElseThrow();
                assertEquals("검사 OFF의 MC는 기존 STRUCTURED 기준과 일치해야 함",frozenRange.get("multipleChoice"),oldRun.mc());
                assertEquals("검사 OFF의 OX는 기존 STRUCTURED 기준과 일치해야 함",frozenRange.get("ox"),oldRun.ox());
                assertEquals(frozenRange.get("randomTail"),oldRun.tail());
                var q=questionDiff(sample.pdf().getFileName().toString(),sample.first(),sample.last(),"PDF_MIXED30","STRUCTURED",oldRun.questions(),newRun.questions(),oldRun.tail(),newRun.tail());
                q.put("generationDiagnosticsChanged",!oldRun.mc().equals(newRun.mc())||!oldRun.ox().equals(newRun.ox())); questions.add(q);
            }
            List<Object> eval = new ArrayList<>();
            try(var files=Files.list(Path.of("sample2"))) {
                for(Path p:files.filter(x->Files.isRegularFile(x,LinkOption.NOFOLLOW_LINKS)&&x.toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).sorted().toList()) {
                    int total=new PdfReader().pageCount(p);
                    for(int first=1;first<=total;first+=50)for(var mode:TermExtractor.Mode.values()) {
                        int last=Math.min(first+49,total);var input=new TermExtractor().extract(new PdfReader().readRange(p,first,last),first,mode);
                        var c=context(support,p,first,last,"PDF_MIXED30",mode.name(),input,input);
                        var oldRun=support.replay(input,false);var newRun=support.replay(input,true);
                        c.put("oldQuestions",oldRun.questions().size());c.put("newQuestions",newRun.questions().size());eval.add(c);
                    }
                }
            }
            Map<String,Object> root=new LinkedHashMap<>();root.put("contexts",contexts);root.put("questionDiffs",questions);root.put("sample2",eval);
            root.put("frozenMcEqual",baseline.equals(actual));
            Path output=Path.of("tmp/bracket-audit/impact.json");Files.createDirectories(output.getParent());
            Files.writeString(output,QuizJson.write(root));
            System.out.println("BRACKET_IMPACT " + output + "; MC baseline equal=" + baseline.equals(actual));
        }
    }
    private static Map<String,Object> context(BracketRuleTestSupport support,Path pdf,int first,int last,String path,String mode,
            TermExtractor.Result input,TermExtractor.Result source) throws Exception {
        support.assertMonotonic(input);
        var before=support.withoutRule(input);var after=support.withRule(input);List<Object> removed=new ArrayList<>(),added=new ArrayList<>(),overlap=new ArrayList<>();
        for(Term t:before)if(!after.contains(t))removed.add(term(t,source,support.beforeReason(t),BracketRuleTestSupport.afterReason(t)));
        for(Term t:after)if(!before.contains(t))added.add(term(t,source,support.beforeReason(t),BracketRuleTestSupport.afterReason(t)));
        for(Term t:input.terms()) {
            String old=support.beforeReason(t),now=BracketRuleTestSupport.afterReason(t);
            if(old!=null&&now!=null&&now.contains(BracketRuleTestSupport.REASON))overlap.add(term(t,source,old,now));
        }
        var out=new LinkedHashMap<String,Object>();out.put("pdf",pdf.getFileName().toString());out.put("sha256",McBaselineCapture.sha(pdf));
        out.put("first",first);out.put("last",last);out.put("path",path);out.put("mode",mode);out.put("before",before.size());out.put("after",after.size());
        out.put("removed",removed);out.put("added",added);out.put("alreadyExcluded",overlap);return out;
    }
    private static Map<String,Object> term(Term t,TermExtractor.Result source,String before,String after) {
        var out=new LinkedHashMap<String,Object>();var evidence=source.evidence().stream().filter(e->e.term().term().equals(t.term())&&(t.page()==0||e.term().page()==t.page())).findFirst();
        out.put("page",evidence.map(e->e.termLine().page()).orElse(t.page()));out.put("runtimePage",t.page());out.put("line",evidence.map(e->e.termLine().lineNumber()).orElse(null));
        out.put("title",excerpt(t.term()));out.put("description",excerpt(t.description()));out.put("descriptionSha256",sha(t.description()));
        out.put("beforeReason",before);out.put("afterReason",after);return out;
    }
    private static Map<String,Object> questionDiff(String pdf,int first,int last,String path,String mode,List<Map<String,Object>> before,List<Map<String,Object>> after,Object oldTail,Object newTail) {
        List<Object> changes=new ArrayList<>();
        for(int i=0;i<Math.max(before.size(),after.size());i++) {
            var a=i<before.size()?before.get(i):null;var b=i<after.size()?after.get(i):null;
            if(!Objects.equals(a,b))changes.add(Map.of("position",i+1,"before",safeQuestion(a),"after",safeQuestion(b)));
        }
        int common=0;List<Map<String,Object>> remaining=new ArrayList<>(after);
        for(var a:before) {
            var fields=comparisonFields(a);int found=-1;
            for(int i=0;i<remaining.size();i++)if(fields.equals(comparisonFields(remaining.get(i)))){found=i;break;}
            if(found>=0){common++;remaining.remove(found);}
        }
        var out=new LinkedHashMap<String,Object>();out.put("pdf",pdf);out.put("first",first);out.put("last",last);out.put("path",path);out.put("mode",mode);
        out.put("beforeCount",before.size());out.put("afterCount",after.size());out.put("changedPositions",changes.size());out.put("removedOrChanged",before.size()-common);
        out.put("addedOrChanged",after.size()-common);out.put("sameQuestionsIgnoringOrder",common);out.put("randomTailChanged",!Objects.equals(oldTail,newTail));out.put("changes",changes);return out;
    }
    private static Map<String,Object> comparisonFields(Map<String,Object> q) {
        var out=new LinkedHashMap<String,Object>();for(String field:List.of("type","question","options","correctIndex","page","lineNumber"))out.put(field,q.get(field));return out;
    }
    private static Object safeQuestion(Map<String,Object> q) {
        if(q==null)return "없음";
        var out=new LinkedHashMap<String,Object>();out.put("type",q.get("type"));out.put("question",excerpt((String)q.get("question")));
        out.put("questionSha256",sha((String)q.get("question")));out.put("options",((List<?>)q.get("options")).stream().map(x->excerpt((String)x)).toList());
        out.put("optionsSha256",((List<?>)q.get("options")).stream().map(x->sha((String)x)).toList());out.put("correctIndex",q.get("correctIndex"));
        out.put("page",q.get("page"));out.put("lineNumber",q.get("lineNumber"));return out;
    }
    static String excerpt(String text) {return text.codePointCount(0,text.length())>100?text.substring(0,text.offsetByCodePoints(0,100)):text;}
    private static String sha(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new AssertionError(e);}
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
}
