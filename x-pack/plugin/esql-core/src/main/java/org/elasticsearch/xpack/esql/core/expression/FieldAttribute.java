/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.core.expression;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.core.util.PlanStreamInput;
import org.elasticsearch.xpack.esql.core.util.PlanStreamOutput;
import org.elasticsearch.xpack.esql.core.util.StringUtils;

import java.io.IOException;
import java.util.Objects;

/**
 * Attribute for an ES field.
 * To differentiate between the different type of fields this class offers:
 * - name - the fully qualified name (foo.bar.tar)
 * - path - the path pointing to the field name (foo.bar)
 * - parent - the immediate parent of the field; useful for figuring out the type of field (nested vs object)
 * - nestedParent - if nested, what's the parent (which might not be the immediate one)
 */
public class FieldAttribute extends TypedAttribute {

    static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        Attribute.class,
        "FieldAttribute",
        FieldAttribute::readFrom
    );

    private final FieldAttribute parent;
    private final String path;
    private final EsField field;

    public FieldAttribute(Source source, String name, EsField field) {
        this(source, null, name, field);
    }

    public FieldAttribute(Source source, FieldAttribute parent, String name, EsField field) {
        this(source, parent, name, field, Nullability.TRUE, null, false);
    }

    public FieldAttribute(Source source, FieldAttribute parent, String name, EsField field, boolean synthetic) {
        this(source, parent, name, field, Nullability.TRUE, null, synthetic);
    }

    public FieldAttribute(
        Source source,
        FieldAttribute parent,
        String name,
        EsField field,
        Nullability nullability,
        NameId id,
        boolean synthetic
    ) {
        this(source, parent, name, field.getDataType(), field, nullability, id, synthetic);
    }

    /**
     * Used only for testing. Do not use this otherwise, as an explicitly set type will be ignored the next time this FieldAttribute is
     * {@link FieldAttribute#clone}d.
     */
    FieldAttribute(
        Source source,
        FieldAttribute parent,
        String name,
        DataType type,
        EsField field,
        Nullability nullability,
        NameId id,
        boolean synthetic
    ) {
        super(source, name, type, nullability, id, synthetic);
        this.path = parent != null ? parent.name() : StringUtils.EMPTY;
        this.parent = parent;
        this.field = field;
    }

    @Deprecated
    /**
     * Old constructor from when this had a qualifier string. Still needed to not break serialization.
     */
    private FieldAttribute(
        Source source,
        FieldAttribute parent,
        String name,
        DataType type,
        EsField field,
        String qualifier,
        Nullability nullability,
        NameId id,
        boolean synthetic
    ) {
        this(source, parent, name, type, field, nullability, id, synthetic);
    }

    private FieldAttribute(StreamInput in) throws IOException {
        /*
         * The funny casting dance with `(StreamInput & PlanStreamInput) in` is required
         * because we're in esql-core here and the real PlanStreamInput is in
         * esql-proper. And because NamedWriteableRegistry.Entry needs StreamInput,
         * not a PlanStreamInput. And we need PlanStreamInput to handle Source
         * and NameId. This should become a hard cast when we move everything out
         * of esql-core.
         */
        this(
            Source.readFrom((StreamInput & PlanStreamInput) in),
            in.readOptionalWriteable(FieldAttribute::readFrom),
            ((PlanStreamInput) in).readCachedString(),
            DataType.readFrom(in),
            EsField.readFrom(in),
            in.readOptionalString(),
            in.readEnum(Nullability.class),
            NameId.readFrom((StreamInput & PlanStreamInput) in),
            in.readBoolean()
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        if (((PlanStreamOutput) out).writeAttributeCacheHeader(this)) {
            Source.EMPTY.writeTo(out);
            out.writeOptionalWriteable(parent);
            ((PlanStreamOutput) out).writeCachedString(name());
            dataType().writeTo(out);
            field.writeTo(out);
            // We used to write the qualifier here. We can still do if needed in the future.
            out.writeOptionalString(null);
            out.writeEnum(nullable());
            id().writeTo(out);
            out.writeBoolean(synthetic());
        }
    }

    public static FieldAttribute readFrom(StreamInput in) throws IOException {
        return ((PlanStreamInput) in).readAttributeWithCache(FieldAttribute::new);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    protected NodeInfo<FieldAttribute> info() {
        return NodeInfo.create(this, FieldAttribute::new, parent, name(), dataType(), field, (String) null, nullable(), id(), synthetic());
    }

    public FieldAttribute parent() {
        return parent;
    }

    public String path() {
        return path;
    }

    /**
     * The full name of the field in the index, including all parent fields. E.g. {@code parent.subfield.this_field}.
     */
    public String fieldName() {
        // Before 8.15, the field name was the same as the attribute's name.
        // On later versions, the attribute can be renamed when creating synthetic attributes.
        // Because until 8.15, we couldn't set `synthetic` to true due to a bug, in that version such FieldAttributes are marked by their
        // name starting with `$$`.
        if ((synthetic() || name().startsWith(SYNTHETIC_ATTRIBUTE_NAME_PREFIX)) == false) {
            return name();
        }
        return Strings.hasText(path) ? path + "." + field.getName() : field.getName();
    }

    public EsField.Exact getExactInfo() {
        return field.getExactInfo();
    }

    public FieldAttribute exactAttribute() {
        EsField exactField = field.getExactField();
        if (exactField.equals(field) == false) {
            return innerField(exactField);
        }
        return this;
    }

    private FieldAttribute innerField(EsField type) {
        return new FieldAttribute(source(), this, name() + "." + type.getName(), type, nullable(), id(), synthetic());
    }

    @Override
    protected Attribute clone(Source source, String name, DataType type, Nullability nullability, NameId id, boolean synthetic) {
        // Ignore `type`, this must be the same as the field's type.
        return new FieldAttribute(source, parent, name, field, nullability, id, synthetic);
    }

    @Override
    public Attribute withDataType(DataType type) {
        throw new UnsupportedOperationException("FieldAttribute obtains its type from the contained EsField.");
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), path, field);
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj)
            && Objects.equals(path, ((FieldAttribute) obj).path)
            && Objects.equals(field, ((FieldAttribute) obj).field);
    }

    @Override
    protected String label() {
        return "f";
    }

    public EsField field() {
        return field;
    }
}
