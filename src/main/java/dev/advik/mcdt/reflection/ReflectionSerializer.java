package dev.advik.mcdt.reflection;

import dev.advik.mcdt.game.serializers.BlockStateSerializer;
import dev.advik.mcdt.game.serializers.EntitySerializer;
import dev.advik.mcdt.game.serializers.ItemSerializer;
import dev.advik.mcdt.game.serializers.TextSerializer;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Serializes live game objects into JSON structures with depth limiting, cycle detection,
 * type adapters for Minecraft primitives, collection paging, and security redactions.
 */
public final class ReflectionSerializer {
	public static final int DEFAULT_MAX_DEPTH = 1;
	public static final int DEFAULT_COLLECTION_LIMIT = 64;

	private final HandleTable handleTable;
	private final NameService nameService;
	private final ReflectionCache cache;

	public ReflectionSerializer(HandleTable handleTable, NameService nameService, ReflectionCache cache) {
		this.handleTable = handleTable;
		this.nameService = nameService;
		this.cache = cache;
	}

	public HandleTable handleTable() {
		return handleTable;
	}

	public NameService nameService() {
		return nameService;
	}

	public ReflectionCache cache() {
		return cache;
	}

	/**
	 * Inspects an object for GET /v1/objects/{handle}, returning class, depth-limited fields, and methods.
	 */
	public Map<String, Object> inspectObject(Object obj, String handleId, int maxDepth, Integer offset, Integer limit) {
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("$handle", handleId);

		if (obj == null) {
			res.put("$class", "null");
			res.put("fields", Map.of());
			res.put("methods", List.of());
			return res;
		}

		Class<?> clazz = obj.getClass();
		ReflectionSecurity.checkClass(clazz);
		ReflectionCache.ClassMetadata metadata = cache.getMetadata(clazz);

		res.put("$class", metadata.resolvedClassName());

		// If object is a collection or array, serialize its elements
		if (obj instanceof Collection<?> col) {
			res.put("size", col.size());
			res.put("items", serializeCollection(col, 0, maxDepth, new HashSet<>(Collections.newSetFromMap(new IdentityHashMap<>())), offset, limit));
		} else if (clazz.isArray()) {
			int len = Array.getLength(obj);
			res.put("size", len);
			res.put("items", serializeArray(obj, 0, maxDepth, new HashSet<>(Collections.newSetFromMap(new IdentityHashMap<>())), offset, limit));
		}

		// Fields
		Map<String, Object> fieldsMap = new LinkedHashMap<>();
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		visited.add(obj);

		for (ReflectionCache.FieldMetadata f : metadata.fields()) {
			if (f.isStatic()) {
				continue;
			}
			if (ReflectionSecurity.isSensitiveField(clazz, f.resolvedName())) {
				fieldsMap.put(f.resolvedName(), ReflectionSecurity.REDACTED);
				continue;
			}

			try {
				Object val = f.getValue(obj);
				fieldsMap.put(f.resolvedName(), serialize(val, 0, maxDepth, visited, null, null));
			} catch (Throwable t) {
				fieldsMap.put(f.resolvedName(), "<unreadable: " + t.getMessage() + ">");
			}
		}
		res.put("fields", fieldsMap);

		// Methods
		List<Map<String, Object>> methodList = new ArrayList<>();
		for (ReflectionCache.MethodMetadata m : metadata.methods()) {
			Map<String, Object> mInfo = new LinkedHashMap<>();
			mInfo.put("name", m.resolvedName());
			mInfo.put("returnType", m.returnType());
			mInfo.put("parameterTypes", m.parameterTypes());
			mInfo.put("static", m.isStatic());
			methodList.add(mInfo);
		}
		res.put("methods", methodList);

		return res;
	}

	/**
	 * Inspects static members of a class for GET /v1/classes/{name}.
	 */
	public Map<String, Object> inspectClass(Class<?> clazz, int maxDepth) {
		ReflectionSecurity.checkClass(clazz);
		ReflectionCache.ClassMetadata metadata = cache.getMetadata(clazz);

		Map<String, Object> res = new LinkedHashMap<>();
		res.put("$class", metadata.resolvedClassName());
		if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
			res.put("superClass", nameService.resolveClassName(clazz.getSuperclass().getName()));
		}
		List<String> interfaces = new ArrayList<>();
		for (Class<?> iface : clazz.getInterfaces()) {
			interfaces.add(nameService.resolveClassName(iface.getName()));
		}
		res.put("interfaces", interfaces);

		Map<String, Object> staticFields = new LinkedHashMap<>();
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

		for (ReflectionCache.FieldMetadata f : metadata.fields()) {
			if (!f.isStatic()) {
				continue;
			}
			if (ReflectionSecurity.isSensitiveField(clazz, f.resolvedName())) {
				staticFields.put(f.resolvedName(), ReflectionSecurity.REDACTED);
				continue;
			}

			try {
				Object val = f.getValue(null);
				staticFields.put(f.resolvedName(), serialize(val, 0, maxDepth, visited, null, null));
			} catch (Throwable t) {
				staticFields.put(f.resolvedName(), "<unreadable: " + t.getMessage() + ">");
			}
		}
		res.put("staticFields", staticFields);

		List<Map<String, Object>> staticMethods = new ArrayList<>();
		for (ReflectionCache.MethodMetadata m : metadata.methods()) {
			if (!m.isStatic()) {
				continue;
			}
			Map<String, Object> mInfo = new LinkedHashMap<>();
			mInfo.put("name", m.resolvedName());
			mInfo.put("returnType", m.returnType());
			mInfo.put("parameterTypes", m.parameterTypes());
			staticMethods.add(mInfo);
		}
		res.put("staticMethods", staticMethods);

		return res;
	}

	/**
	 * General serializer for values with depth limiting, cycle detection, and adapters.
	 */
	public Object serialize(Object obj, int depth, int maxDepth, Set<Object> visited, Integer offset, Integer limit) {
		if (obj == null) {
			return null;
		}

		if (ReflectionSecurity.isSensitiveObject(obj)) {
			return ReflectionSecurity.REDACTED;
		}

		// Primitives & direct JSON types
		if (obj instanceof Number || obj instanceof Boolean || obj instanceof Character || obj instanceof String) {
			return obj;
		}
		if (obj instanceof Enum<?> e) {
			return e.name();
		}
		if (obj instanceof UUID uuid) {
			return uuid.toString();
		}

		// Minecraft type adapters
		if (obj instanceof Vec3 vec) {
			return Map.of("x", vec.x, "y", vec.y, "z", vec.z);
		}
		if (obj instanceof Vec3i pos) {
			return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
		}
		if (obj instanceof ResourceLocation res) {
			return res.toString();
		}
		if (obj instanceof Component comp) {
			String plain = TextSerializer.toPlainText(comp);
			String json = TextSerializer.toJson(comp);
			return Map.of("text", plain != null ? plain : "", "json", json != null ? json : "");
		}
		if (obj instanceof ItemStack stack) {
			return ItemSerializer.toSlotInfo(0, stack);
		}
		if (obj instanceof BlockState bs) {
			return BlockStateSerializer.toString(bs);
		}
		if (obj instanceof Entity ent) {
			return EntitySerializer.toEntityInfo(ent, null);
		}

		// Collections & Arrays
		if (obj instanceof Collection<?> col) {
			if (depth >= maxDepth) {
				String handle = handleTable.getOrCreateHandle(obj);
				return Map.of("$handle", handle, "$class", nameService.resolveClassName(col.getClass().getName()), "size", col.size());
			}
			List<Object> items = serializeCollection(col, depth, maxDepth, visited, offset, limit);
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("$type", "collection");
			map.put("size", col.size());
			map.put("items", items);
			if (col.size() > items.size()) {
				map.put("truncated", true);
			}
			return map;
		}

		if (obj.getClass().isArray()) {
			int len = Array.getLength(obj);
			if (depth >= maxDepth) {
				String handle = handleTable.getOrCreateHandle(obj);
				return Map.of("$handle", handle, "$class", nameService.resolveClassName(obj.getClass().getName()), "size", len);
			}
			List<Object> items = serializeArray(obj, depth, maxDepth, visited, offset, limit);
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("$type", "array");
			map.put("size", len);
			map.put("items", items);
			if (len > items.size()) {
				map.put("truncated", true);
			}
			return map;
		}

		if (obj instanceof Map<?, ?> map) {
			if (depth >= maxDepth) {
				String handle = handleTable.getOrCreateHandle(obj);
				return Map.of("$handle", handle, "$class", nameService.resolveClassName(map.getClass().getName()), "size", map.size());
			}
			Map<String, Object> outMap = new LinkedHashMap<>();
			int count = 0;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (count++ >= DEFAULT_COLLECTION_LIMIT) {
					break;
				}
				String key = String.valueOf(entry.getKey());
				outMap.put(key, serialize(entry.getValue(), depth + 1, maxDepth, visited, null, null));
			}
			return outMap;
		}

		// Cycle detection
		if (visited.contains(obj)) {
			String handle = handleTable.getOrCreateHandle(obj);
			return Map.of("$handle", handle, "$class", nameService.resolveClassName(obj.getClass().getName()));
		}

		// Depth limit hit -> return handle descriptor
		if (depth >= maxDepth) {
			String handle = handleTable.getOrCreateHandle(obj);
			return Map.of("$handle", handle, "$class", nameService.resolveClassName(obj.getClass().getName()));
		}

		// Expand object fields
		visited.add(obj);
		try {
			Class<?> clazz = obj.getClass();
			ReflectionSecurity.checkClass(clazz);
			ReflectionCache.ClassMetadata metadata = cache.getMetadata(clazz);

			Map<String, Object> out = new LinkedHashMap<>();
			String handle = handleTable.getOrCreateHandle(obj);
			out.put("$handle", handle);
			out.put("$class", metadata.resolvedClassName());

			Map<String, Object> fieldsMap = new LinkedHashMap<>();
			for (ReflectionCache.FieldMetadata f : metadata.fields()) {
				if (f.isStatic()) {
					continue;
				}
				if (ReflectionSecurity.isSensitiveField(clazz, f.resolvedName())) {
					fieldsMap.put(f.resolvedName(), ReflectionSecurity.REDACTED);
					continue;
				}

				try {
					Object val = f.getValue(obj);
					fieldsMap.put(f.resolvedName(), serialize(val, depth + 1, maxDepth, visited, null, null));
				} catch (Throwable t) {
					fieldsMap.put(f.resolvedName(), "<unreadable: " + t.getMessage() + ">");
				}
			}
			out.put("fields", fieldsMap);
			return out;
		} finally {
			visited.remove(obj);
		}
	}

	private List<Object> serializeCollection(Collection<?> col, int depth, int maxDepth, Set<Object> visited, Integer offset, Integer limit) {
		int start = offset != null ? Math.max(0, offset) : 0;
		int count = limit != null ? Math.min(DEFAULT_COLLECTION_LIMIT, Math.max(0, limit)) : DEFAULT_COLLECTION_LIMIT;

		List<Object> items = new ArrayList<>();
		int idx = 0;
		for (Object item : col) {
			if (idx >= start && items.size() < count) {
				items.add(serialize(item, depth + 1, maxDepth, visited, null, null));
			}
			idx++;
			if (items.size() >= count) {
				break;
			}
		}
		return items;
	}

	private List<Object> serializeArray(Object array, int depth, int maxDepth, Set<Object> visited, Integer offset, Integer limit) {
		int len = Array.getLength(array);
		int start = offset != null ? Math.max(0, offset) : 0;
		int count = limit != null ? Math.min(DEFAULT_COLLECTION_LIMIT, Math.max(0, limit)) : DEFAULT_COLLECTION_LIMIT;

		List<Object> items = new ArrayList<>();
		for (int i = start; i < len && items.size() < count; i++) {
			Object item = Array.get(array, i);
			items.add(serialize(item, depth + 1, maxDepth, visited, null, null));
		}
		return items;
	}
}
