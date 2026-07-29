package org.ratden.skavenblight.event.skavenIncursion.planning.persistence.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared strict NBT-reading and writing helpers for incursion persistence
 * codecs.
 *
 * Persisted active incursions are authoritative runtime state. Missing,
 * malformed or unknown values are therefore rejected rather than replaced
 * with guessed defaults.
 *
 * Format-version migration belongs to the relevant top-level persistence
 * codec. These helpers only read the schema they are explicitly given.
 */
public final class IncursionSnapshotNbtSupport {

    private static final String UUID_VALUE_KEY =
            "value";

    private static final String MAP_ENTRY_KEY =
            "key";

    private static final String MAP_ENTRY_VALUE =
            "value";

    private IncursionSnapshotNbtSupport() {
    }

    public static UUID requireUuid(
            CompoundTag tag,
            String key
    ) {
        requireTag(
                tag
        );

        if (!tag.hasUUID(
                key
        )) {
            throw missingOrInvalid(
                    key,
                    "UUID"
            );
        }

        return tag.getUUID(
                key
        );
    }

    public static String requireString(
            CompoundTag tag,
            String key
    ) {
        requireTag(
                tag
        );

        if (!tag.contains(
                key,
                Tag.TAG_STRING
        )) {
            throw missingOrInvalid(
                    key,
                    "string"
            );
        }

        String value =
                tag.getString(
                        key
                );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "NBT field '"
                            + key
                            + "' cannot be blank."
            );
        }

        return value;
    }

    public static int requireInt(
            CompoundTag tag,
            String key
    ) {
        requireTag(
                tag
        );

        if (!tag.contains(
                key,
                Tag.TAG_INT
        )) {
            throw missingOrInvalid(
                    key,
                    "integer"
            );
        }

        return tag.getInt(
                key
        );
    }

    public static long requireLong(
            CompoundTag tag,
            String key
    ) {
        requireTag(
                tag
        );

        if (!tag.contains(
                key,
                Tag.TAG_LONG
        )) {
            throw missingOrInvalid(
                    key,
                    "long"
            );
        }

        return tag.getLong(
                key
        );
    }

    public static double requireDouble(
            CompoundTag tag,
            String key
    ) {
        requireTag(
                tag
        );

        if (!tag.contains(
                key,
                Tag.TAG_DOUBLE
        )) {
            throw missingOrInvalid(
                    key,
                    "double"
            );
        }

        return tag.getDouble(
                key
        );
    }

    public static boolean requireBoolean(
            CompoundTag tag,
            String key
    ) {
        requireTag(
                tag
        );

        if (!tag.contains(
                key,
                Tag.TAG_BYTE
        )) {
            throw missingOrInvalid(
                    key,
                    "boolean"
            );
        }

        return tag.getBoolean(
                key
        );
    }

    public static CompoundTag requireCompound(
            CompoundTag tag,
            String key
    ) {
        requireTag(
                tag
        );

        Tag storedTag =
                tag.get(
                        key
                );

        if (!(storedTag instanceof CompoundTag compoundTag)) {
            throw missingOrInvalid(
                    key,
                    "compound"
            );
        }

        return compoundTag;
    }

    public static CompoundTag readOptionalCompound(
            CompoundTag tag,
            String key
    ) {
        requireTag(
                tag
        );

        Tag storedTag =
                tag.get(
                        key
                );

        if (storedTag == null) {
            return null;
        }

        if (!(storedTag instanceof CompoundTag compoundTag)) {
            throw missingOrInvalid(
                    key,
                    "compound"
            );
        }

        return compoundTag;
    }

    public static ListTag requireCompoundList(
            CompoundTag tag,
            String key
    ) {
        return requireList(
                tag,
                key,
                Tag.TAG_COMPOUND
        );
    }

    public static ListTag requireStringList(
            CompoundTag tag,
            String key
    ) {
        return requireList(
                tag,
                key,
                Tag.TAG_STRING
        );
    }

    public static <E extends Enum<E>> E requireEnum(
            CompoundTag tag,
            String key,
            Class<E> enumType
    ) {
        if (enumType == null) {
            throw new IllegalArgumentException(
                    "Enum type cannot be null."
            );
        }

        String enumName =
                requireString(
                        tag,
                        key
                );

        try {
            return Enum.valueOf(
                    enumType,
                    enumName
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "NBT field '"
                            + key
                            + "' contains unknown "
                            + enumType.getSimpleName()
                            + " value '"
                            + enumName
                            + "'.",
                    exception
            );
        }
    }

    public static <E extends Enum<E>> void putEnumList(
            CompoundTag tag,
            String key,
            List<E> values
    ) {
        requireTag(
                tag
        );

        if (values == null) {
            throw new IllegalArgumentException(
                    "Enum value list cannot be null."
            );
        }

        ListTag listTag =
                new ListTag();

        for (E value
                : values) {

            if (value == null) {
                throw new IllegalArgumentException(
                        "Enum value list cannot contain null."
                );
            }

            listTag.add(
                    StringTag.valueOf(
                            value.name()
                    )
            );
        }

        tag.put(
                key,
                listTag
        );
    }

    public static <E extends Enum<E>> List<E> readEnumList(
            CompoundTag tag,
            String key,
            Class<E> enumType
    ) {
        if (enumType == null) {
            throw new IllegalArgumentException(
                    "Enum type cannot be null."
            );
        }

        ListTag listTag =
                requireStringList(
                        tag,
                        key
                );

        List<E> values =
                new ArrayList<>();

        for (int index = 0;
             index < listTag.size();
             index++) {

            String enumName =
                    listTag.getString(
                            index
                    );

            try {
                values.add(
                        Enum.valueOf(
                                enumType,
                                enumName
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "NBT list '"
                                + key
                                + "' contains unknown "
                                + enumType.getSimpleName()
                                + " value '"
                                + enumName
                                + "' at index "
                                + index
                                + ".",
                        exception
                );
            }
        }

        return List.copyOf(
                values
        );
    }

    public static void putUuidList(
            CompoundTag tag,
            String key,
            List<UUID> values
    ) {
        requireTag(
                tag
        );

        if (values == null) {
            throw new IllegalArgumentException(
                    "UUID list cannot be null."
            );
        }

        ListTag listTag =
                new ListTag();

        for (UUID value
                : values) {

            if (value == null) {
                throw new IllegalArgumentException(
                        "UUID list cannot contain null."
                );
            }

            CompoundTag valueTag =
                    new CompoundTag();

            valueTag.putUUID(
                    UUID_VALUE_KEY,
                    value
            );

            listTag.add(
                    valueTag
            );
        }

        tag.put(
                key,
                listTag
        );
    }

    public static List<UUID> readUuidList(
            CompoundTag tag,
            String key
    ) {
        ListTag listTag =
                requireCompoundList(
                        tag,
                        key
                );

        List<UUID> values =
                new ArrayList<>();

        for (int index = 0;
             index < listTag.size();
             index++) {

            CompoundTag valueTag =
                    listTag.getCompound(
                            index
                    );

            try {
                values.add(
                        requireUuid(
                                valueTag,
                                UUID_VALUE_KEY
                        )
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "NBT UUID list '"
                                + key
                                + "' contains an invalid entry at index "
                                + index
                                + ".",
                        exception
                );
            }
        }

        return List.copyOf(
                values
        );
    }

    /**
     * Writes a string-to-integer map as an ordered list of compounds.
     *
     * A list is used instead of one CompoundTag field per map entry so mob IDs
     * remain data values rather than becoming part of the NBT schema.
     */
    public static void putStringIntMap(
            CompoundTag tag,
            String key,
            Map<String, Integer> values
    ) {
        requireTag(
                tag
        );

        if (values == null) {
            throw new IllegalArgumentException(
                    "String-to-integer map cannot be null."
            );
        }

        ListTag entryTags =
                new ListTag();

        for (Map.Entry<String, Integer> entry
                : values.entrySet()) {

            String entryKey =
                    entry.getKey();

            Integer entryValue =
                    entry.getValue();

            if (entryKey == null
                    || entryKey.isBlank()) {

                throw new IllegalArgumentException(
                        "String-to-integer map key cannot be blank."
                );
            }

            if (entryValue == null) {
                throw new IllegalArgumentException(
                        "String-to-integer map value cannot be null."
                );
            }

            CompoundTag entryTag =
                    new CompoundTag();

            entryTag.putString(
                    MAP_ENTRY_KEY,
                    entryKey
            );

            entryTag.putInt(
                    MAP_ENTRY_VALUE,
                    entryValue
            );

            entryTags.add(
                    entryTag
            );
        }

        tag.put(
                key,
                entryTags
        );
    }

    /**
     * Reads a string-to-integer map while preserving its saved iteration
     * order.
     */
    public static Map<String, Integer> readStringIntMap(
            CompoundTag tag,
            String key
    ) {
        ListTag entryTags =
                requireCompoundList(
                        tag,
                        key
                );

        LinkedHashMap<String, Integer> values =
                new LinkedHashMap<>();

        Set<String> seenKeys =
                new HashSet<>();

        for (int index = 0;
             index < entryTags.size();
             index++) {

            CompoundTag entryTag =
                    entryTags.getCompound(
                            index
                    );

            String entryKey;

            int entryValue;

            try {
                entryKey =
                        requireString(
                                entryTag,
                                MAP_ENTRY_KEY
                        );

                entryValue =
                        requireInt(
                                entryTag,
                                MAP_ENTRY_VALUE
                        );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "NBT string-to-integer map '"
                                + key
                                + "' contains an invalid entry at index "
                                + index
                                + ".",
                        exception
                );
            }

            if (!seenKeys.add(
                    entryKey
            )) {
                throw new IllegalArgumentException(
                        "NBT string-to-integer map '"
                                + key
                                + "' contains duplicate key '"
                                + entryKey
                                + "'."
                );
            }

            values.put(
                    entryKey,
                    entryValue
            );
        }

        return Collections.unmodifiableMap(
                values
        );
    }

    private static ListTag requireList(
            CompoundTag tag,
            String key,
            int expectedElementType
    ) {
        requireTag(
                tag
        );

        Tag storedTag =
                tag.get(
                        key
                );

        if (!(storedTag instanceof ListTag listTag)) {
            throw missingOrInvalid(
                    key,
                    "list"
            );
        }

        if (!listTag.isEmpty()
                && listTag.getElementType()
                != expectedElementType) {

            throw new IllegalArgumentException(
                    "NBT list '"
                            + key
                            + "' contains element type "
                            + listTag.getElementType()
                            + " but expected "
                            + expectedElementType
                            + "."
            );
        }

        return listTag;
    }

    private static void requireTag(
            CompoundTag tag
    ) {
        if (tag == null) {
            throw new IllegalArgumentException(
                    "NBT compound cannot be null."
            );
        }
    }

    private static IllegalArgumentException
    missingOrInvalid(
            String key,
            String expectedType
    ) {
        return new IllegalArgumentException(
                "NBT field '"
                        + key
                        + "' is missing or is not a valid "
                        + expectedType
                        + "."
        );
    }
}