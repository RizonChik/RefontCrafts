package ru.refontstudio.refontcrafts.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

public final class YouerCompat {
   private YouerCompat() {
   }

   public static boolean isAir(ItemStack var0) {
      if (var0 == null) {
         return true;
      } else {
         try {
            Object var1 = invokeNoArgs(var0, "getType");
            String var2 = enumName(var1);
            if (var2 != null && !"AIR".equals(var2)) {
               return false;
            }
         } catch (Throwable var6) {
         }

         Object var7 = nmsHandle(var0);
         if (var7 != null) {
            try {
               Object var8 = invokeNoArgs(var7, "isEmpty");
               if (var8 instanceof Boolean) {
                  return (Boolean)var8;
               }
            } catch (Throwable var5) {
            }

            try {
               Object var9 = invokeNoArgs(var7, "getItem");
               if (var9 != null) {
                  String var3 = registryKeyFromNmsItem(var9);
                  if (var3 != null && !"minecraft:air".equals(var3)) {
                     return false;
                  }
               }
            } catch (Throwable var4) {
            }
         }

         return true;
      }
   }

   public static String registryKey(ItemStack var0) {
      if (var0 == null) {
         return null;
      } else {
         Object var1 = nmsHandle(var0);
         if (var1 != null) {
            try {
               Object var2 = invokeNoArgs(var1, "getItem");
               String var3 = registryKeyFromNmsItem(var2);
               if (var3 != null) {
                  return var3;
               }
            } catch (Throwable var6) {
            }
         }

         try {
            Object var7 = invokeNoArgs(var0, "getType");
            if (var7 != null) {
               try {
                  Object var8 = invokeNoArgs(var7, "getKey");
                  if (var8 != null) {
                     return var8.toString();
                  }
               } catch (Throwable var4) {
               }

               String var9 = enumName(var7);
               if (var9 != null && !"AIR".equals(var9)) {
                  return "bukkit:" + var9.toLowerCase(Locale.ROOT);
               }
            }
         } catch (Throwable var5) {
         }

         return null;
      }
   }

   public static String signature(ItemStack var0, Object var1, Object var2) {
      if (isAir(var0)) {
         return null;
      } else {
         boolean var3 = false;

         try {
            Object var4 = invokeNoArgs(var1, "exactMeta");
            var3 = var4 instanceof Boolean && (Boolean)var4;
         } catch (Throwable var13) {
         }

         if (var3) {
            ItemStack var15 = cloneOne(var0);
            return YouerItemCodec.formatString(var15 == null ? var0 : var15);
         } else {
            String var14 = registryKey(var0);
            if (var14 == null || var14.isEmpty() || "minecraft:air".equals(var14)) {
               Object var5 = safeInvokeNoArgs(var0, "getType");
               String var6 = enumName(var5);
               var14 = var6 == null ? "UNKNOWN" : var6;
            }

            StringBuilder var16 = new StringBuilder(var14);
            Object var17 = safeInvokeNoArgs(var0, "getType");
            String var7 = enumName(var17);

            try {
               Object var8 = invokeNoArgs(var0, "getDurability");
               if (var8 instanceof Number) {
                  var16.append("|data=").append(((Number)var8).intValue());
               }
            } catch (Throwable var12) {
            }

            if (var7 != null && var7.contains("POTION")) {
               try {
                  Class var18 = Class.forName("ru.refontstudio.refontcrafts.util.Compat", true, YouerCompat.class.getClassLoader());
                  Method var9 = var18.getMethod("potionSignature", ItemStack.class);
                  Object var10 = var9.invoke((Object)null, var0);
                  if (var10 != null) {
                     var16.append("|potion=").append(var10.toString());
                  }
               } catch (Throwable var11) {
               }
            }

            Object var19 = safeInvokeNoArgs(var0, "getItemMeta");
            if (var19 != null) {
               appendStableMeta(var16, var19);
            }

            return var16.toString();
         }
      }
   }

   public static String describe(ItemStack var0) {
      if (var0 == null) {
         return "null";
      } else {
         Object var1 = safeInvokeNoArgs(var0, "getType");
         String var2 = enumName(var1);
         String var3 = registryKey(var0);
         Object var4 = nmsHandle(var0);
         return "class=" + var0.getClass().getName() + ", type=" + var2 + ", key=" + var3 + ", nms=" + (var4 == null ? "null" : var4.getClass().getName()) + ", air=" + isAir(var0);
      }
   }

   static ItemStack cloneOne(ItemStack var0) {
      if (var0 == null) {
         return null;
      } else {
         try {
            Method var1 = findMethod(var0.getClass(), "clone", 0);
            Object var2 = var1 == null ? null : var1.invoke(var0);
            if (var2 instanceof ItemStack) {
               try {
                  Method var3 = findMethod(var2.getClass(), "setAmount", 1);
                  if (var3 != null) {
                     var3.invoke(var2, 1);
                  }
               } catch (Throwable var4) {
               }

               return (ItemStack)var2;
            }
         } catch (Throwable var5) {
         }

         return var0;
      }
   }

   static Object nmsHandle(ItemStack var0) {
      if (var0 == null) {
         return null;
      } else {
         Class var1 = var0.getClass();

         for(Class var2 = var1; var2 != null; var2 = var2.getSuperclass()) {
            try {
               Field var3 = var2.getDeclaredField("handle");
               var3.setAccessible(true);
               Object var4 = var3.get(var0);
               if (var4 != null) {
                  return var4;
               }
            } catch (Throwable var8) {
            }
         }

         try {
            Class var10 = findCraftItemStackClass(var0.getClass().getClassLoader());
            if (var10 != null) {
               for(Method var6 : var10.getMethods()) {
                  if (Modifier.isStatic(var6.getModifiers()) && "asNMSCopy".equals(var6.getName()) && var6.getParameterCount() == 1) {
                     Object var7 = var6.invoke((Object)null, var0);
                     if (var7 != null) {
                        return var7;
                     }
                  }
               }
            }
         } catch (Throwable var9) {
         }

         return null;
      }
   }

   static Class<?> findCraftItemStackClass(ClassLoader var0) {
      if (var0 == null) {
         var0 = YouerCompat.class.getClassLoader();
      }

      String[] var1 = new String[]{"org.bukkit.craftbukkit.inventory.CraftItemStack", "org.bukkit.craftbukkit.v1_21_R1.inventory.CraftItemStack", "org.bukkit.craftbukkit.v1_20_R4.inventory.CraftItemStack"};

      for(String var5 : var1) {
         try {
            return Class.forName(var5, true, var0);
         } catch (Throwable var8) {
         }
      }

      try {
         Class var9 = Class.forName("org.bukkit.Bukkit", true, var0);
         Object var10 = var9.getMethod("getServer").invoke((Object)null);
         if (var10 != null) {
            Package var11 = var10.getClass().getPackage();
            if (var11 != null) {
               return Class.forName(var11.getName() + ".inventory.CraftItemStack", true, var0);
            }
         }
      } catch (Throwable var7) {
      }

      return null;
   }

   static String registryKeyFromNmsItem(Object var0) {
      if (var0 == null) {
         return null;
      } else {
         try {
            ClassLoader var1 = var0.getClass().getClassLoader();
            if (var1 == null) {
               var1 = YouerCompat.class.getClassLoader();
            }

            Class var2 = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, var1);
            Field var3 = var2.getField("ITEM");
            Object var4 = var3.get((Object)null);
            if (var4 == null) {
               return null;
            } else {
               Method var5 = null;

               for(Method var9 : var4.getClass().getMethods()) {
                  if ("getKey".equals(var9.getName()) && var9.getParameterCount() == 1) {
                     var5 = var9;
                     break;
                  }
               }

               if (var5 == null) {
                  return null;
               } else {
                  Object var11 = var5.invoke(var4, var0);
                  return var11 == null ? null : var11.toString();
               }
            }
         } catch (Throwable var10) {
            return null;
         }
      }
   }

   static ItemStack createFromRegistry(String var0, int var1) {
      if (var0 != null && !var0.isEmpty()) {
         try {
            ClassLoader var2 = YouerCompat.class.getClassLoader();
            Class var3 = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, var2);
            Object var4 = var3.getField("ITEM").get((Object)null);
            if (var4 == null) {
               return null;
            }

            Object var5 = createResourceLocation(var0, var2);
            if (var5 == null) {
               return null;
            }

            Object var6 = null;

            for(Method var10 : var4.getClass().getMethods()) {
               if (("get".equals(var10.getName()) || "getValue".equals(var10.getName())) && var10.getParameterCount() == 1) {
                  Class var11 = var10.getParameterTypes()[0];
                  if (var11.isAssignableFrom(var5.getClass())) {
                     try {
                        var6 = var10.invoke(var4, var5);
                        if (var6 != null) {
                           break;
                        }
                     } catch (Throwable var16) {
                     }
                  }
               }
            }

            if (var6 == null) {
               return null;
            }

            Class var18 = Class.forName("net.minecraft.world.item.ItemStack", true, var2);
            Object var19 = null;

            for(Constructor var12 : var18.getConstructors()) {
               Class[] var13 = var12.getParameterTypes();
               if (var13.length == 2 && var13[1] == Integer.TYPE && var13[0].isAssignableFrom(var6.getClass())) {
                  var19 = var12.newInstance(var6, Math.max(1, var1));
                  break;
               }

               if (var13.length == 1 && var13[0].isAssignableFrom(var6.getClass())) {
                  var19 = var12.newInstance(var6);

                  try {
                     Method var14 = findMethod(var19.getClass(), "setCount", 1);
                     if (var14 != null) {
                        var14.invoke(var19, Math.max(1, var1));
                     }
                  } catch (Throwable var15) {
                  }
                  break;
               }
            }

            if (var19 == null) {
               return null;
            }

            Class var21 = findCraftItemStackClass(var2);
            if (var21 == null) {
               return null;
            }

            for(Method var27 : var21.getMethods()) {
               if (Modifier.isStatic(var27.getModifiers()) && "asBukkitCopy".equals(var27.getName()) && var27.getParameterCount() == 1) {
                  Object var28 = var27.invoke((Object)null, var19);
                  if (var28 instanceof ItemStack) {
                     return (ItemStack)var28;
                  }
               }
            }
         } catch (Throwable var17) {
         }

         return null;
      } else {
         return null;
      }
   }

   private static Object createResourceLocation(String var0, ClassLoader var1) {
      try {
         Class var2 = Class.forName("net.minecraft.resources.ResourceLocation", true, var1);

         for(Method var6 : var2.getMethods()) {
            if (Modifier.isStatic(var6.getModifiers()) && var6.getParameterCount() == 1 && var6.getParameterTypes()[0] == String.class && ("parse".equals(var6.getName()) || "tryParse".equals(var6.getName()))) {
               try {
                  Object var7 = var6.invoke((Object)null, var0);
                  if (var7 != null) {
                     return var7;
                  }
               } catch (Throwable var8) {
               }
            }
         }

         try {
            Constructor var13 = var2.getDeclaredConstructor(String.class);
            var13.setAccessible(true);
            return var13.newInstance(var0);
         } catch (Throwable var10) {
            int var12 = var0.indexOf(58);
            if (var12 > 0) {
               try {
                  Constructor var14 = var2.getDeclaredConstructor(String.class, String.class);
                  var14.setAccessible(true);
                  return var14.newInstance(var0.substring(0, var12), var0.substring(var12 + 1));
               } catch (Throwable var9) {
               }
            }
         }
      } catch (Throwable var11) {
      }

      return null;
   }

   private static void appendStableMeta(StringBuilder var0, Object var1) {
      try {
         Object var2 = invokeNoArgs(var1, "hasDisplayName");
         if (Boolean.TRUE.equals(var2)) {
            Object var3 = invokeNoArgs(var1, "getDisplayName");
            if (var3 != null) {
               var0.append("|name=").append(escape(var3.toString()));
            }
         }
      } catch (Throwable var8) {
      }

      try {
         Object var11 = invokeNoArgs(var1, "hasLore");
         if (Boolean.TRUE.equals(var11)) {
            Object var15 = invokeNoArgs(var1, "getLore");
            if (var15 instanceof Iterable) {
               var0.append("|lore=");

               for(Object var5 : (Iterable)var15) {
                  var0.append(escape(String.valueOf(var5))).append('\u001f');
               }
            }
         }
      } catch (Throwable var10) {
      }

      try {
         Object var12 = invokeNoArgs(var1, "hasCustomModelData");
         if (Boolean.TRUE.equals(var12)) {
            Object var16 = invokeNoArgs(var1, "getCustomModelData");
            if (var16 != null) {
               var0.append("|cmd=").append(var16.toString());
            }
         }
      } catch (Throwable var7) {
      }

      try {
         Object var13 = invokeNoArgs(var1, "isUnbreakable");
         if (Boolean.TRUE.equals(var13)) {
            var0.append("|unbreakable=true");
         }
      } catch (Throwable var6) {
      }

      try {
         Map<Object, Object> enchants = new LinkedHashMap<Object, Object>();
         Object var14 = safeInvokeNoArgs(var1, "getEnchants");
         if (var14 instanceof Map) enchants.putAll((Map<?, ?>)var14);
         Object stored = safeInvokeNoArgs(var1, "getStoredEnchants");
         if (stored instanceof Map) enchants.putAll((Map<?, ?>)stored);
         if (!enchants.isEmpty()) {
            ArrayList<String> var17 = new ArrayList<String>();

            for(Object entry : enchants.entrySet()) {
               Map.Entry<?, ?> var20 = (Map.Entry<?, ?>)entry;
               var17.add(var20.getKey() + ":" + var20.getValue());
            }

            Collections.sort(var17);
            var0.append("|ench=");

            for(String var21 : var17) {
               var0.append(escape(var21)).append(',');
            }
         }
      } catch (Throwable var9) {
      }

   }

   private static String escape(String var0) {
      return var0 == null ? "" : var0.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n").replace("\r", "\\r");
   }

   static Object safeInvokeNoArgs(Object var0, String var1) {
      try {
         return invokeNoArgs(var0, var1);
      } catch (Throwable var3) {
         return null;
      }
   }

   static Object invokeNoArgs(Object var0, String var1) throws Exception {
      if (var0 == null) {
         return null;
      } else {
         Method var2 = findMethod(var0.getClass(), var1, 0);
         if (var2 == null) {
            throw new NoSuchMethodException(var1);
         } else {
            var2.setAccessible(true);
            return var2.invoke(var0);
         }
      }
   }

   static Method findMethod(Class<?> var0, String var1, int var2) {
      for(Class var3 = var0; var3 != null; var3 = var3.getSuperclass()) {
         for(Method var7 : var3.getDeclaredMethods()) {
            if (var1.equals(var7.getName()) && var7.getParameterCount() == var2) {
               var7.setAccessible(true);
               return var7;
            }
         }
      }

      for(Method var11 : var0.getMethods()) {
         if (var1.equals(var11.getName()) && var11.getParameterCount() == var2) {
            return var11;
         }
      }

      return null;
   }

   static String enumName(Object var0) {
      if (var0 == null) {
         return null;
      } else if (var0 instanceof Enum) {
         return ((Enum)var0).name();
      } else {
         try {
            Object var1 = invokeNoArgs(var0, "name");
            if (var1 != null) {
               return var1.toString();
            }
         } catch (Throwable var2) {
         }

         return var0.toString();
      }
   }
}
