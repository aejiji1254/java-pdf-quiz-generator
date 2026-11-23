$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    mvn -q package '-DskipTests'
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }

    mvn -q dependency:build-classpath '-Dmdep.outputFile=target/gui-classpath.txt' '-Dmdep.includeScope=runtime'
    if ($LASTEXITCODE -ne 0) { throw 'Maven classpath generation failed.' }

    $quizDependencies = (Get-Content -LiteralPath 'target/gui-classpath.txt' -Raw -Encoding UTF8).Trim()
    foreach ($dependency in ($quizDependencies -split ';')) {
        if (-not (Test-Path -LiteralPath $dependency -PathType Leaf)) {
            throw "Runtime dependency not found: $dependency"
        }
    }
    $quizClasspath = 'target\classes;' + $quizDependencies
    java -cp $quizClasspath com.quiz.gui.QuizMakerGui
    if ($LASTEXITCODE -ne 0) { throw 'GUI launch failed.' }
} finally {
    Pop-Location
}
