/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.http.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.smithy.java.codecs.commons.internal.codegen.RuntimeCodegenFeature;
import software.amazon.smithy.java.core.schema.PreludeSchemas;
import software.amazon.smithy.java.core.schema.Schema;
import software.amazon.smithy.java.core.schema.SerializableStruct;
import software.amazon.smithy.java.core.schema.ShapeBuilder;
import software.amazon.smithy.java.core.serde.Codec;
import software.amazon.smithy.java.core.serde.MemberSubsetCodec;
import software.amazon.smithy.java.core.serde.ShapeDeserializer;
import software.amazon.smithy.java.core.serde.ShapeSerializer;
import software.amazon.smithy.java.http.api.HttpHeaders;
import software.amazon.smithy.java.http.api.HttpRequest;
import software.amazon.smithy.java.http.api.HttpResponse;
import software.amazon.smithy.java.http.api.HttpVersion;
import software.amazon.smithy.java.io.datastream.DataStream;
import software.amazon.smithy.java.json.JsonCodec;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.HttpHeaderTrait;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;
import software.amazon.smithy.model.traits.HttpResponseCodeTrait;

/**
 * A body key that names a member bound to another part of the HTTP message (label, header, query, status code) must
 * be skipped like an unknown key: it must not break parsing, and it must never override the HTTP-bound value.
 *
 * <p>Both the interpreted path ({@code ShapeDeserializer.readStruct}) and the generated direct-body path
 * ({@link MemberSubsetCodec} with {@link BodyMemberSubset}) are covered.
 */
public class HttpBoundMemberInBodyTest {

    private static final List<String> BODY_VALUES = List.of(
            "\"9\"",
            "42",
            "true",
            "null",
            "{\"a\":[1,{\"b\":null}],\"c\":\"}\"}",
            "[1,\"x\",{},[]]");

    enum Path {
        INTERPRETED,
        GENERATED
    }

    static List<Arguments> requestCases() {
        List<Arguments> result = new ArrayList<>();
        for (Path path : Path.values()) {
            for (String key : List.of("itemId", "token", "filter")) {
                for (String value : BODY_VALUES) {
                    result.add(Arguments.of(path, key, value));
                }
            }
        }
        return result;
    }

    static List<Arguments> responseCases() {
        List<Arguments> result = new ArrayList<>();
        for (Path path : Path.values()) {
            for (String key : List.of("code", "token")) {
                for (String value : BODY_VALUES) {
                    result.add(Arguments.of(path, key, value));
                }
            }
        }
        return result;
    }

    static List<Path> paths() {
        return List.of(Path.values());
    }

    @ParameterizedTest(name = "{0} request body key {1}={2}")
    @MethodSource("requestCases")
    public void requestSkipsBodyKeyForHttpBoundMember(Path path, String key, String value) {
        // Bound key first (the reported failure), then the same key after the body member.
        assertRequest(path, "{\"" + key + "\":" + value + ",\"name\":\"n\"}");
        assertRequest(path, "{\"name\":\"n\",\"" + key + "\":" + value + "}");
    }

    @ParameterizedTest(name = "{0} request without bound key")
    @MethodSource("paths")
    public void requestWithoutBoundKeyIsUnchanged(Path path) {
        assertRequest(path, "{\"name\":\"n\"}");
        assertRequest(path, "{\"zzz\":{\"q\":[1]},\"name\":\"n\"}");
    }

    @ParameterizedTest(name = "{0} response body key {1}={2}")
    @MethodSource("responseCases")
    public void responseSkipsBodyKeyForHttpBoundMember(Path path, String key, String value) {
        assertResponse(path, "{\"" + key + "\":" + value + ",\"name\":\"n\"}");
        assertResponse(path, "{\"name\":\"n\",\"" + key + "\":" + value + "}");
    }

    @ParameterizedTest(name = "{0} response without bound key")
    @MethodSource("paths")
    public void responseWithoutBoundKeyIsUnchanged(Path path) {
        assertResponse(path, "{\"name\":\"n\"}");
    }

    private static void assertRequest(Path path, String body) {
        var codec = codec(path);
        var request = HttpRequest.create()
                .setMethod("POST")
                .setUri(URI.create("https://example.com/items/label-id?filter=query-filter"))
                .setHeaders(Map.of("X-Token", List.of("header-token")))
                .setBody(DataStream.ofString(body));
        var builder = new ItemInput.Builder();

        new RequestDeserializer()
                .payloadCodec(codec)
                .request(request)
                .pathLabelValues(Map.of("itemId", "label-id"))
                .inputShapeBuilder(builder)
                .deserialize();

        assertEquals(new ItemInput("label-id", "header-token", "query-filter", "n"), builder.build(), body);
        codec.assertPath(path);
    }

    private static void assertResponse(Path path, String body) {
        var codec = codec(path);
        var response = HttpResponse.of(
                HttpVersion.HTTP_1_1,
                201,
                HttpHeaders.of(Map.of("X-Token", List.of("header-token"))),
                DataStream.ofString(body));
        var builder = new ItemOutput.Builder();

        new ResponseDeserializer()
                .payloadCodec(codec)
                .response(response)
                .outputShapeBuilder(builder)
                .deserialize();

        assertEquals(new ItemOutput(201, "header-token", "n"), builder.build(), body);
        codec.assertPath(path);
    }

    private static RecordingCodec codec(Path path) {
        if (path == Path.GENERATED) {
            assumeTrue(RuntimeCodegenFeature.available(), "Runtime code generation is not available in this JVM");
            return new RecordingCodec(JsonCodec.builder().runtimeCodegen(true).build());
        }
        return new RecordingCodec(JsonCodec.builder().runtimeCodegen(false).build());
    }

    /** Delegates to a JSON codec and records whether the generated direct-body path consumed the body. */
    private static final class RecordingCodec implements Codec, MemberSubsetCodec {
        private final JsonCodec delegate;
        private boolean direct;

        RecordingCodec(JsonCodec delegate) {
            this.delegate = delegate;
        }

        void assertPath(Path expected) {
            assertEquals(expected == Path.GENERATED, direct, "generated direct-body path used");
        }

        @Override
        public ShapeSerializer createSerializer(OutputStream sink) {
            return delegate.createSerializer(sink);
        }

        @Override
        public ShapeDeserializer createDeserializer(ByteBuffer source) {
            return delegate.createDeserializer(source);
        }

        @Override
        public ByteBuffer serialize(SerializableStruct struct, MemberSubset subset) {
            return delegate.serialize(struct, subset);
        }

        @Override
        public boolean deserialize(Schema schema, ShapeBuilder<?> builder, ByteBuffer source, MemberSubset subset) {
            direct = delegate.deserialize(schema, builder, source, subset);
            return direct;
        }
    }

    public record ItemInput(String itemId, String token, String filter, String name) implements SerializableStruct {
        static final Schema SCHEMA = Schema.structureBuilder(ShapeId.from("smithy.example#ItemInput"))
                .shapeClass(ItemInput.class)
                .builderSupplier(Builder::new)
                .putMember("itemId", PreludeSchemas.STRING, new HttpLabelTrait())
                .putMember("token", PreludeSchemas.STRING, new HttpHeaderTrait("X-Token"))
                .putMember("filter", PreludeSchemas.STRING, new HttpQueryTrait("filter"))
                .putMember("name", PreludeSchemas.STRING)
                .build();

        public static Builder builder() {
            return new Builder();
        }

        @Override
        public Schema schema() {
            return SCHEMA;
        }

        @Override
        public void serializeMembers(ShapeSerializer serializer) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getMemberValue(Schema member) {
            return (T) switch (member.memberName()) {
                case "itemId" -> itemId;
                case "token" -> token;
                case "filter" -> filter;
                case "name" -> name;
                default -> null;
            };
        }

        public static final class Builder implements ShapeBuilder<ItemInput> {
            private String itemId;
            private String token;
            private String filter;
            private String name;

            public Builder itemId(String itemId) {
                this.itemId = itemId;
                return this;
            }

            public Builder token(String token) {
                this.token = token;
                return this;
            }

            public Builder filter(String filter) {
                this.filter = filter;
                return this;
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            @Override
            public ItemInput build() {
                return new ItemInput(itemId, token, filter, name);
            }

            @Override
            public ShapeBuilder<ItemInput> deserialize(ShapeDeserializer decoder) {
                decoder.readStruct(SCHEMA, this, (b, member, de) -> {
                    String value = de.readString(member);
                    switch (member.memberName()) {
                        case "itemId" -> b.itemId(value);
                        case "token" -> b.token(value);
                        case "filter" -> b.filter(value);
                        case "name" -> b.name(value);
                        default -> throw new IllegalStateException(member.memberName());
                    }
                });
                return this;
            }

            @Override
            public Schema schema() {
                return SCHEMA;
            }
        }
    }

    public record ItemOutput(Integer code, String token, String name) implements SerializableStruct {
        static final Schema SCHEMA = Schema.structureBuilder(ShapeId.from("smithy.example#ItemOutput"))
                .shapeClass(ItemOutput.class)
                .builderSupplier(Builder::new)
                .putMember("code", PreludeSchemas.INTEGER, new HttpResponseCodeTrait())
                .putMember("token", PreludeSchemas.STRING, new HttpHeaderTrait("X-Token"))
                .putMember("name", PreludeSchemas.STRING)
                .build();

        public static Builder builder() {
            return new Builder();
        }

        @Override
        public Schema schema() {
            return SCHEMA;
        }

        @Override
        public void serializeMembers(ShapeSerializer serializer) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getMemberValue(Schema member) {
            return (T) switch (member.memberName()) {
                case "code" -> code;
                case "token" -> token;
                case "name" -> name;
                default -> null;
            };
        }

        public static final class Builder implements ShapeBuilder<ItemOutput> {
            private Integer code;
            private String token;
            private String name;

            public Builder code(Integer code) {
                this.code = code;
                return this;
            }

            public Builder token(String token) {
                this.token = token;
                return this;
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            @Override
            public ItemOutput build() {
                return new ItemOutput(code, token, name);
            }

            @Override
            public ShapeBuilder<ItemOutput> deserialize(ShapeDeserializer decoder) {
                decoder.readStruct(SCHEMA, this, (b, member, de) -> {
                    switch (member.memberName()) {
                        case "code" -> b.code(de.readInteger(member));
                        case "token" -> b.token(de.readString(member));
                        case "name" -> b.name(de.readString(member));
                        default -> throw new IllegalStateException(member.memberName());
                    }
                });
                return this;
            }

            @Override
            public Schema schema() {
                return SCHEMA;
            }
        }
    }
}
