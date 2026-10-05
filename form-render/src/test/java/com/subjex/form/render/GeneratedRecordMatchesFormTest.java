package com.subjex.form.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.subjex.form.generated.EndpointPublication;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GeneratedRecordMatchesFormTest {

    @Test
    void checkedInRecordMatchesTheFormFields() throws IOException {
        RenderedForm form = new FormRenderer().render(FormRendererTest.formYaml());
        String generated = new FormRecordGenerator().source(form, "com.subjex.form.generated");
        String checkedIn = Files.readString(checkedInSource());

        assertEquals(generated, checkedIn.replace("\r\n", "\n"));

        RecordComponent[] components = EndpointPublication.class.getRecordComponents();
        assertEquals(form.fields().size(), components.length);
        for (int index = 0; index < components.length; index++) {
            FormField field = form.fields().get(index);
            assertEquals(field.name(), components[index].getName());
            Class<?> expected = field.kind() == FieldKind.INTEGER ? int.class : String.class;
            assertEquals(expected, components[index].getType());
        }
    }

    private static Path checkedInSource() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(
                    "form-render/src/main/java/com/subjex/form/generated/EndpointPublication.java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("checked-in EndpointPublication.java was not found");
    }
}
