package com.qwerlty.myojbackendquestionservice.validation;

import com.qwerlty.myojbackendcommon.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SubmissionLanguageValidatorTest {

    private final SubmissionLanguageValidator validator = new SubmissionLanguageValidator();

    @Test
    void rejectsCppSourceSubmittedAsJava() {
        String code = "#include <bits/stdc++.h>\n"
                + "using namespace std;\n"
                + "int main() { return 0; }\n";

        assertThatThrownBy(() -> validator.validate("java", code))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Java")
                .hasMessageContaining("C++");
    }

    @Test
    void acceptsTheSameCppSourceWhenCppIsSelected() {
        String code = "#include <bits/stdc++.h>\n"
                + "using namespace std;\n"
                + "int main() { return 0; }\n";

        assertDoesNotThrow(() -> validator.validate("cpp", code));
    }

    @Test
    void rejectsOtherHighConfidenceLanguageMismatches() {
        String javaCode = "public class Main { public static void main(String[] args) {} }";
        String goCode = "package main\nfunc main() {}";

        assertThatThrownBy(() -> validator.validate("cpp", javaCode))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("C++")
                .hasMessageContaining("Java");
        assertThatThrownBy(() -> validator.validate("java", goCode))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Java")
                .hasMessageContaining("Go");
    }

    @Test
    void doesNotMisclassifyLanguageNamesInsideComments() {
        String javaCode = "// C++ example: #include <iostream>\n"
                + "public class Main { public static void main(String[] args) {} }";

        assertDoesNotThrow(() -> validator.validate("java", javaCode));
    }

    @Test
    void rejectsBlankCodeBeforeCreatingAJudgeTask() {
        assertThatThrownBy(() -> validator.validate("java", "  \n\t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("代码不能为空");
    }
}
