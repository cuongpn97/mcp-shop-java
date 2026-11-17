package com.example.shop.mcp.core;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON Schema (2020-12) of a tool's arguments, generated once at startup from the record. */
public final class ToolSchemas {

    public static final String CONFIRM_TOKEN = "confirm_token";
    private static final SchemaGenerator GENERATOR;

    static {
        SchemaGeneratorConfigBuilder config = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON)
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS));
        config.forFields().withDescriptionResolver(field -> {
            Description d = field.getAnnotationConsideringFieldAndGetter(Description.class);
            return d == null ? null : d.value();
        });
        GENERATOR = new SchemaGenerator(config.build());
    }

    private ToolSchemas() {
    }

    public static Map<String, Object> inputSchema(ToolSpec<?> spec, JsonMapper json) {
        Map<String, Object> schema = json.convertValue(GENERATOR.generateSchema(spec.argsType()), new TypeReference<>() {
        });
        schema.remove("$schema");
        schema.put("additionalProperties", false);
        if (spec.confirm()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.computeIfAbsent("properties",
                    k -> new LinkedHashMap<>());
            props.put(CONFIRM_TOKEN, Map.of("type", "string", "description",
                    "Omit on the first call: it returns a preview and a confirm_token and writes nothing. Show the "
                            + "preview to the user; only after an explicit yes call again with the SAME arguments "
                            + "plus confirm_token."));
        }
        return schema;
    }
}
