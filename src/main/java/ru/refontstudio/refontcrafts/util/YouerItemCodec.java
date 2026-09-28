package ru.refontstudio.refontcrafts.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import org.bukkit.inventory.ItemStack;

public final class YouerItemCodec {
   private static final String NBT_PREFIX = "NBT64:";
   private static final String OLD_PREFIX = "B64:";
   private static final String REG_PREFIX = "REG64:";

   private YouerItemCodec() {
   }

   public static String formatString(ItemStack var0) {
      if (YouerCompat.isAir(var0)) {
         return "AIR:1";
      } else {
         try {
            Method var1 = YouerCompat.findMethod(var0.getClass(), "serializeAsBytes", 0);
            if (var1 != null) {
               Object var2 = var1.invoke(var0);
               if (var2 instanceof byte[] && ((byte[])var2).length > 0) {
                  return "NBT64:" + Base64.getEncoder().encodeToString((byte[])var2);
               }
            }
         } catch (Throwable var8) {
         }

         try {
            ByteArrayOutputStream var11 = new ByteArrayOutputStream();
            Class var14 = Class.forName("org.bukkit.util.io.BukkitObjectOutputStream", true, var0.getClass().getClassLoader());
            Constructor var16 = var14.getConstructor(OutputStream.class);
            Object var4 = var16.newInstance(var11);
            Method var5 = var14.getMethod("writeObject", Object.class);
            var5.invoke(var4, var0);
            Method var6 = var14.getMethod("close");
            var6.invoke(var4);
            return "B64:" + Base64.getEncoder().encodeToString(var11.toByteArray());
         } catch (Throwable var9) {
            String var10 = YouerCompat.registryKey(var0);
            if (var10 != null && !var10.isEmpty() && !"minecraft:air".equals(var10)) {
               int var13 = amount(var0);
               String var15 = Base64.getUrlEncoder().withoutPadding().encodeToString(var10.getBytes(StandardCharsets.UTF_8));
               return "REG64:" + var15 + ":" + Math.max(1, var13);
            } else {
               try {
                  Object var12 = YouerCompat.invokeNoArgs(var0, "getType");
                  String var3 = YouerCompat.enumName(var12);
                  if (var3 != null) {
                     return var3 + ":" + Math.max(1, amount(var0));
                  }
               } catch (Throwable var7) {
               }

               return "AIR:1";
            }
         }
      }
   }

   public static ItemStack parseString(String var0) {
      if (var0 != null && !var0.trim().isEmpty()) {
         String var1 = var0.trim();
         if (var1.startsWith("NBT64:")) {
            try {
               byte[] var2 = Base64.getDecoder().decode(var1.substring("NBT64:".length()));
               Class var3 = Class.forName("org.bukkit.inventory.ItemStack", true, YouerItemCodec.class.getClassLoader());
               Method var4 = var3.getMethod("deserializeBytes", byte[].class);
               Object var5 = var4.invoke((Object)null, var2);
               if (var5 instanceof ItemStack) {
                  return (ItemStack)var5;
               }
            } catch (Throwable var13) {
            }
         }

         if (var1.startsWith("B64:")) {
            try {
               byte[] var14 = Base64.getDecoder().decode(var1.substring("B64:".length()));
               ByteArrayInputStream var16 = new ByteArrayInputStream(var14);
               Class var18 = Class.forName("org.bukkit.util.io.BukkitObjectInputStream", true, YouerItemCodec.class.getClassLoader());
               Constructor var20 = var18.getConstructor(InputStream.class);
               Object var6 = var20.newInstance(var16);
               Method var7 = var18.getMethod("readObject");
               Object var8 = var7.invoke(var6);

               try {
                  var18.getMethod("close").invoke(var6);
               } catch (Throwable var11) {
               }

               if (var8 instanceof ItemStack) {
                  return (ItemStack)var8;
               }
            } catch (Throwable var12) {
            }
         }

         if (var1.startsWith("REG64:")) {
            try {
               int var15 = var1.lastIndexOf(58);
               String var17 = var15 > "REG64:".length() ? var1.substring("REG64:".length(), var15) : var1.substring("REG64:".length());
               int var19 = var15 > "REG64:".length() ? Math.max(1, Integer.parseInt(var1.substring(var15 + 1))) : 1;
               String var21 = new String(Base64.getUrlDecoder().decode(var17), StandardCharsets.UTF_8);
               ItemStack var22 = YouerCompat.createFromRegistry(var21, var19);
               if (var22 != null) {
                  return var22;
               }
            } catch (Throwable var10) {
            }
         }

         return parseLegacyMaterial(var1);
      } else {
         return air();
      }
   }

   private static ItemStack parseLegacyMaterial(String var0) {
      try {
         String[] var1 = var0.split("[: ]");
         String var2 = var1[0].trim().toUpperCase(Locale.ROOT);
         int var3 = 1;
         if (var1.length > 1) {
            try {
               var3 = Math.max(1, Integer.parseInt(var1[1]));
            } catch (Throwable var17) {
            }
         }

         ClassLoader var4 = YouerItemCodec.class.getClassLoader();
         Class var5 = Class.forName("org.bukkit.Material", true, var4);
         Method var6 = var5.getMethod("valueOf", String.class);
         Object var7 = var6.invoke((Object)null, var2);
         if (var7 == null || "AIR".equals(YouerCompat.enumName(var7))) {
            return air();
         }

         Class var8 = Class.forName("org.bukkit.inventory.ItemStack", true, var4);

         for(Constructor var12 : var8.getConstructors()) {
            Class[] var13 = var12.getParameterTypes();
            if (var13.length == 2 && var13[1] == Integer.TYPE && var13[0].isAssignableFrom(var7.getClass())) {
               Object var14 = var12.newInstance(var7, var3);
               if (var14 instanceof ItemStack) {
                  return (ItemStack)var14;
               }
            }

            if (var13.length == 1 && var13[0].isAssignableFrom(var7.getClass())) {
               Object var19 = var12.newInstance(var7);
               if (var19 instanceof ItemStack) {
                  try {
                     Method var15 = YouerCompat.findMethod(var19.getClass(), "setAmount", 1);
                     if (var15 != null) {
                        var15.invoke(var19, var3);
                     }
                  } catch (Throwable var16) {
                  }

                  return (ItemStack)var19;
               }
            }
         }
      } catch (Throwable var18) {
      }

      return air();
   }

   private static ItemStack air() {
      try {
         ClassLoader var0 = YouerItemCodec.class.getClassLoader();
         Class var1 = Class.forName("org.bukkit.Material", true, var0);
         Object var2 = var1.getField("AIR").get((Object)null);
         Class var3 = Class.forName("org.bukkit.inventory.ItemStack", true, var0);

         for(Constructor var7 : var3.getConstructors()) {
            Class[] var8 = var7.getParameterTypes();
            if (var8.length == 1 && var8[0].isAssignableFrom(var2.getClass())) {
               Object var9 = var7.newInstance(var2);
               if (var9 instanceof ItemStack) {
                  return (ItemStack)var9;
               }
            }
         }
      } catch (Throwable var10) {
      }

      return null;
   }

   private static int amount(ItemStack var0) {
      try {
         Object var1 = YouerCompat.invokeNoArgs(var0, "getAmount");
         if (var1 instanceof Number) {
            return ((Number)var1).intValue();
         }
      } catch (Throwable var2) {
      }

      return 1;
   }
}
