/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.http.binding;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import software.amazon.smithy.java.core.schema.Schema;
import software.amazon.smithy.java.core.serde.Codec;
import software.amazon.smithy.java.core.serde.ShapeDeserializer;
import software.amazon.smithy.java.core.serde.document.Document;

final class PayloadDeserializer implements ShapeDeserializer {
    private final Codec payloadCodec;
    private final ByteBuffer bodyByteBuffer;

    PayloadDeserializer(Codec payloadCodec, ByteBuffer bodyByteBuffer) {
        this.payloadCodec = payloadCodec;
        this.bodyByteBuffer = bodyByteBuffer;
    }

    private ShapeDeserializer createDeserializer() {
        return payloadCodec.createDeserializer(bodyByteBuffer);
    }

    @Override
    public boolean readBoolean(Schema schema) {
        try (var deser = createDeserializer()) {
            return deser.readBoolean(schema);
        }
    }

    @Override
    public ByteBuffer readBlob(Schema schema) {
        if (isNull()) {
            return null;
        }

        return bodyByteBuffer;
    }

    @Override
    public byte readByte(Schema schema) {
        try (var deser = createDeserializer()) {
            return deser.readByte(schema);
        }
    }

    @Override
    public short readShort(Schema schema) {
        try (var deser = createDeserializer()) {
            return deser.readShort(schema);
        }
    }

    @Override
    public int readInteger(Schema schema) {
        try (var deser = createDeserializer()) {
            return deser.readInteger(schema);
        }
    }

    @Override
    public long readLong(Schema schema) {
        try (var deser = createDeserializer()) {
            return deser.readLong(schema);
        }
    }

    @Override
    public float readFloat(Schema schema) {
        try (var deser = createDeserializer()) {
            return deser.readFloat(schema);
        }
    }

    @Override
    public double readDouble(Schema schema) {
        try (var deser = createDeserializer()) {
            return deser.readDouble(schema);
        }
    }

    @Override
    public BigInteger readBigInteger(Schema schema) {
        if (isNull()) {
            return null;
        }

        try (var deser = createDeserializer()) {
            return deser.readBigInteger(schema);
        }
    }

    @Override
    public BigDecimal readBigDecimal(Schema schema) {
        if (isNull()) {
            return null;
        }

        try (var deser = createDeserializer()) {
            return deser.readBigDecimal(schema);
        }
    }

    @Override
    public String readString(Schema schema) {
        if (isNull()) {
            return null;
        }

        if (bodyByteBuffer.hasArray()) {
            int pos = bodyByteBuffer.arrayOffset() + bodyByteBuffer.position();
            int len = bodyByteBuffer.remaining();
            return new String(bodyByteBuffer.array(), pos, len, StandardCharsets.UTF_8);
        }

        return StandardCharsets.UTF_8.decode(bodyByteBuffer).toString();
    }

    @Override
    public Document readDocument() {
        if (isNull()) {
            return null;
        }

        try (var deser = createDeserializer()) {
            return deser.readDocument();
        }
    }

    @Override
    public Instant readTimestamp(Schema schema) {
        if (isNull()) {
            return null;
        }

        try (var deser = createDeserializer()) {
            return deser.readTimestamp(schema);
        }
    }

    @Override
    public <T> void readStruct(Schema schema, T state, StructMemberConsumer<T> consumer) {
        if (!isNull()) {
            try (var deser = createDeserializer()) {
                deser.readStruct(schema, state, consumer);
            }
        }
    }

    @Override
    public <T> void readList(Schema schema, T state, ListMemberConsumer<T> consumer) {
        if (!isNull()) {
            try (var deser = createDeserializer()) {
                deser.readList(schema, state, consumer);
            }
        }
    }

    @Override
    public <T> void readStringMap(Schema schema, T state, MapMemberConsumer<String, T> consumer) {
        if (!isNull()) {
            try (var deser = createDeserializer()) {
                deser.readStringMap(schema, state, consumer);
            }
        }
    }

    @Override
    public boolean isNull() {
        return bodyByteBuffer == null;
    }
}
