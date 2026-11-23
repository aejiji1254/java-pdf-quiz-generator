# 프로젝트: 강의 PDF 시험 대비 퀴즈 생성기 (Java 콘솔 및 Swing)

## 범위
- 입력: 텍스트 기반 PDF(최대 50페이지씩 선택) 또는 사용자가 검토한 수동 용어·설명
- 재료: 출처 줄을 추적할 수 있는 명확한 용어·설명 관계(Term)
- 문제 유형: O/X 및 4지선다(기본 30, 사용자 지정 가능). 안전한 근거가 없으면 출제 제외
- 제외: 스캔 PDF의 OCR, 근거 없는 설명·오답 생성, 웹 UI

## 기술
- Maven, Java 25, PDFBox 3.0.3, JUnit 4. 외부 AI API 금지
- 기존 콘솔 메뉴와 Swing GUI, UTF-8 JSON 문제·학습 기록, 오답 Stack 유지
- 핵심 클래스: PdfReader / Term / TermExtractor / QuizGenerator / Main / gui.QuizWorkflow

## 작업 규칙
- 한 번에 한 단계만 구현하고, 구현 전에 계획을 먼저 설명할 것
- 요청하지 않은 기능은 추가하지 말 것
- 각 단계마다 정상/실패 케이스 테스트(JUnit 4, 이미 pom에 있음)를 함께 작성
- 바꾼 파일과 이유를 마지막에 요약할 것
- pom.xml은 허락 없이 수정하지 말 것
