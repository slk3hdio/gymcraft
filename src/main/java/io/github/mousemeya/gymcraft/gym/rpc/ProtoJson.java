package io.github.mousemeya.gymcraft.gym.rpc;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

final class ProtoJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ProtoJson() {
    }

    static String toJson(Map<String, Object> value) {
        return GSON.toJson(value);
    }

    static Struct toStruct(Map<String, Object> map) {
        Struct.Builder builder = Struct.newBuilder();
        if (map == null) {
            return builder.build();
        }
        for (var entry : map.entrySet()) {
            builder.putFields(entry.getKey(), toValue(entry.getValue()));
        }
        return builder.build();
    }

    static Map<String, Object> fromStruct(Struct struct) {
        var map = new LinkedHashMap<String, Object>();
        if (struct == null) {
            return map;
        }
        for (var entry : struct.getFieldsMap().entrySet()) {
            map.put(entry.getKey(), fromValue(entry.getValue()));
        }
        return map;
    }

    private static Value toValue(Object value) {
        Value.Builder builder = Value.newBuilder();
        if (value == null) {
            return builder.setNullValue(NullValue.NULL_VALUE).build();
        }
        if (value instanceof Boolean booleanValue) {
            return builder.setBoolValue(booleanValue).build();
        }
        if (value instanceof Number numberValue) {
            return builder.setNumberValue(numberValue.doubleValue()).build();
        }
        if (value instanceof CharSequence charSequence) {
            return builder.setStringValue(charSequence.toString()).build();
        }
        if (value instanceof Map<?, ?> mapValue) {
            Struct.Builder struct = Struct.newBuilder();
            for (var entry : mapValue.entrySet()) {
                struct.putFields(String.valueOf(entry.getKey()), toValue(entry.getValue()));
            }
            return builder.setStructValue(struct).build();
        }
        if (value instanceof Iterable<?> iterableValue) {
            ListValue.Builder list = ListValue.newBuilder();
            for (Object element : iterableValue) {
                list.addValues(toValue(element));
            }
            return builder.setListValue(list).build();
        }
        if (value.getClass().isArray()) {
            ListValue.Builder list = ListValue.newBuilder();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                list.addValues(toValue(Array.get(value, i)));
            }
            return builder.setListValue(list).build();
        }
        return builder.setStringValue(String.valueOf(value)).build();
    }

    private static Object fromValue(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> fromStruct(value.getStructValue());
            case LIST_VALUE -> fromList(value.getListValue());
            case KIND_NOT_SET -> null;
        };
    }

    private static List<Object> fromList(ListValue value) {
        var list = new ArrayList<Object>(value.getValuesCount());
        for (Value element : value.getValuesList()) {
            list.add(fromValue(element));
        }
        return list;
    }
}
