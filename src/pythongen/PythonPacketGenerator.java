package pythongen;


import mindustry.Vars;
import arc.struct.ObjectMap;
import arc.func.Prov;

import mindustry.net.Packet;
import mindustry.net.Packets;
import mindustry.gen.*; // Where auto-generated packets usually live
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

public class PythonPacketGenerator {

    // Map Java primitive/common types to our Python DataBuffer methods
    private static final Map<Class<?>, String[]> TYPE_MAP = new HashMap<>();
    static {
        // Format: {Python Type, Read Method, Write Method}
        TYPE_MAP.put(int.class, new String[]{"int", "read_int", "write_int"});
        TYPE_MAP.put(float.class, new String[]{"float", "read_float", "write_float"});
        TYPE_MAP.put(short.class, new String[]{"int", "read_short", "write_short"});
        TYPE_MAP.put(byte.class, new String[]{"int", "read_byte", "write_byte"});
        TYPE_MAP.put(boolean.class, new String[]{"bool", "read_byte", "write_byte"});
        TYPE_MAP.put(String.class, new String[]{"str", "read_arc_string", "write_arc_string"});
        TYPE_MAP.put(long.class, new String[]{"int", "read_long", "write_long"});
    }

    public static void generatePythonLibrary(String outputPath) {
        try (PrintWriter writer = new PrintWriter(outputPath)) {
            writer.println("\"\"\"\nAuto-generated Mindustry Packet Library via Java Reflection.\n\"\"\"");
            writer.println("from typing import Any");
            writer.println("from mindustry_client import Packet, DataBuffer\n");

            // Reflectively access Net.serializer.idToProv (replacement for deprecated Packets.all)
            Class<?>[] registeredPackets;
            try {
                Field netField = Vars.class.getField("net");
                Object net = netField.get(null);
                Field serializerField = net.getClass().getDeclaredField("serializer");
                serializerField.setAccessible(true);
                Object serializer = serializerField.get(net);
                Field mapField = serializer.getClass().getDeclaredField("idToProv");
                mapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                ObjectMap<Integer, Prov<? extends Packet>> idToProv =
                        (ObjectMap<Integer, Prov<? extends Packet>>) mapField.get(serializer);
                registeredPackets = new Class<?>[idToProv.size];
                idToProv.each((id,prov) -> {
                    // int id = entry.key;
                    // Prov<? extends Packet> prov = entry.value;
                    if (prov != null && prov.get() != null) {
                        registeredPackets[id] = prov.get().getClass();
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException("Failed to reflect Net.serializer packet registry", e);
            }


            // Arc network internal packets usually take IDs 0-9. Mindustry packets start after.
            // We can calculate the exact ID by checking how Arc registers them, but usually 
            // the index in Packets.all + the Arc offset gives the exact network ID.
            int arcOffset = 10; 

            for (int i = 0; i < registeredPackets.length; i++) {
                Class<?> clazz = registeredPackets[i];
                if (clazz == null || !Packet.class.isAssignableFrom(clazz)) continue;

                int packetId = i + arcOffset;
                String className = clazz.getSimpleName();

                writer.println("class " + className + "(Packet):");
                writer.println("    ID = " + packetId + "\n");

                Field[] fields = clazz.getDeclaredFields();
                
                // 1. Generate __init__
                writer.print("    def __init__(self");
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                    
                    String[] typeInfo = TYPE_MAP.getOrDefault(field.getType(), new String[]{"Any", "", ""});
                    String pyType = typeInfo[0];
                    String defaultVal = pyType.equals("bool") ? "False" : (pyType.equals("str") ? "''" : "0");
                    if (pyType.equals("Any")) defaultVal = "None";
                    
                    writer.print(", " + field.getName() + ": " + pyType + " = " + defaultVal);
                }
                writer.println("):");
                
                boolean hasFields = false;
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                    writer.println("        self." + field.getName() + " = " + field.getName());
                    hasFields = true;
                }
                if (!hasFields) writer.println("        pass");
                writer.println();

                // 2. Generate write()
                writer.println("    def write(self, buffer: DataBuffer) -> None:");
                hasFields = false;
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                    String[] typeInfo = TYPE_MAP.get(field.getType());
                    
                    if (typeInfo != null) {
                        if (typeInfo[0].equals("bool")) {
                            writer.println("        buffer." + typeInfo[2] + "(1 if self." + field.getName() + " else 0)");
                        } else {
                            writer.println("        buffer." + typeInfo[2] + "(self." + field.getName() + ")");
                        }
                    } else {
                        writer.println("        # TODO: Serialize complex Java type: " + field.getType().getSimpleName());
                    }
                    hasFields = true;
                }
                if (!hasFields) writer.println("        pass");
                writer.println();

                // 3. Generate read()
                writer.println("    def read(self, buffer: DataBuffer) -> None:");
                hasFields = false;
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                    String[] typeInfo = TYPE_MAP.get(field.getType());
                    
                    if (typeInfo != null) {
                        if (typeInfo[0].equals("bool")) {
                            writer.println("        self." + field.getName() + " = buffer." + typeInfo[1] + "() != 0");
                        } else {
                            writer.println("        self." + field.getName() + " = buffer." + typeInfo[1] + "()");
                        }
                    } else {
                        writer.println("        # TODO: Deserialize complex Java type: " + field.getType().getSimpleName());
                    }
                    hasFields = true;
                }
                if (!hasFields) writer.println("        pass");
                writer.println("\n");
            }
            System.out.println("Successfully generated Python packets to " + outputPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}