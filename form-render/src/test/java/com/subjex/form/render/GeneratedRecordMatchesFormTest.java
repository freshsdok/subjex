package com.subjex.form.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.subjex.form.generated.ConfigOverride;
import com.subjex.form.generated.EndpointPublication;
import com.subjex.form.generated.ServiceNote;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * GeneratedRecordMatchesFormTest — 已检入记录与 YAML 一致：生成器写出的源码必须与仓库里的文件相同。
 */
class GeneratedRecordMatchesFormTest {

    @Test
    void endpointPublicationMatchesTheFormFields() throws IOException {
        assertCheckedInMatches(
                "forms/endpoint-publication.form.yaml",
                "form-render/src/main/java/com/subjex/form/generated/EndpointPublication.java",
                EndpointPublication.class);
    }

    @Test
    void configOverrideMatchesTheFormFields() throws IOException {
        assertCheckedInMatches(
                "forms/config-override.form.yaml",
                "form-render/src/main/java/com/subjex/form/generated/ConfigOverride.java",
                ConfigOverride.class);
    }

    @Test
    void serviceNoteMatchesTheFormFields() throws IOException {
        assertCheckedInMatches(
                "forms/service-note.form.yaml",
                "form-render/src/main/java/com/subjex/form/generated/ServiceNote.java",
                ServiceNote.class);
    }

    private static void assertCheckedInMatches(String classpathYaml, String sourceRelative, Class<?> recordType)
            throws IOException {
        String yaml;
        try (var in = GeneratedRecordMatchesFormTest.class.getClassLoader().getResourceAsStream(classpathYaml)) {
            if (in == null) {
                throw new IllegalStateException(classpathYaml + " missing on classpath");
            }
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        RenderedForm form = new FormRenderer().render(yaml);
        String generated = new FormRecordGenerator().source(form, "com.subjex.form.generated");
        String checkedIn = Files.readString(checkedInSource(sourceRelative));
        assertEquals(generated, checkedIn.replace("\r\n", "\n"));

        RecordComponent[] components = recordType.getRecordComponents();
        assertEquals(form.fields().size(), components.length);
        for (int index = 0; index < components.length; index++) {
            FormField field = form.fields().get(index);
            assertEquals(field.name(), components[index].getName());
            Class<?> expected = switch (field.kind()) {
                case INTEGER -> field.required() ? int.class : Integer.class;
                case BOOLEAN -> field.required() ? boolean.class : Boolean.class;
                case TEXT, DATE, ENUM, SUBJECT_REF, ORGANIZATION_REF -> String.class;
            };
            assertEquals(expected, components[index].getType());
        }
    }

    private static Path checkedInSource(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(relative + " was not found");
    }
}
