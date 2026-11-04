import com.fasterxml.jackson.databind.json.JsonMapper;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Reflect the compiled SDK without executing constructors or loading credentials. */
public class ApiSurface {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "target/classes" : args[0]);
        Map<String, String> surface = new TreeMap<>();
        try (var files = Files.walk(root.resolve("ai/originchain"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name =
                        root.relativize(file)
                                .toString()
                                .replace(java.io.File.separatorChar, '.')
                                .replaceFirst("\\.class$", "");
                Class<?> type = Class.forName(name, false, ApiSurface.class.getClassLoader());
                if (!Modifier.isPublic(type.getModifiers())) continue;
                surface.put(
                        name,
                        Modifier.toString(type.getModifiers())
                                + " extends "
                                + type.getGenericSuperclass()
                                + " implements "
                                + Arrays.toString(type.getGenericInterfaces()));
                for (Constructor<?> item : type.getDeclaredConstructors()) {
                    if (Modifier.isPublic(item.getModifiers())
                            || Modifier.isProtected(item.getModifiers()))
                        surface.put(
                                name + ".new" + Arrays.toString(item.getParameterTypes()),
                                item.toGenericString());
                }
                for (Method item : type.getDeclaredMethods()) {
                    if ((Modifier.isPublic(item.getModifiers())
                                    || Modifier.isProtected(item.getModifiers()))
                            && !item.isSynthetic())
                        surface.put(
                                name
                                        + "."
                                        + item.getName()
                                        + Arrays.toString(item.getParameterTypes()),
                                item.toGenericString());
                }
                for (Field item : type.getDeclaredFields()) {
                    if (Modifier.isPublic(item.getModifiers())
                            || Modifier.isProtected(item.getModifiers()))
                        surface.put(name + "." + item.getName(), item.toGenericString());
                }
            }
        }
        System.out.println(JsonMapper.builder().build().writeValueAsString(surface));
    }
}
