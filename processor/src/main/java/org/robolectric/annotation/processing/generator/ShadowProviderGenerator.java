package org.robolectric.annotation.processing.generator;

import com.google.common.base.Joiner;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.processing.RobolectricModel;
import org.robolectric.annotation.processing.RobolectricProcessor;

/**
 * Generator that creates the "ShadowProvider" implementation for a shadow package.
 */
public class ShadowProviderGenerator extends Generator {
  private final Filer filer;
  private final Messager messager;
  private final Elements elements;
  private final RobolectricModel model;
  private final String shadowPackage;
  private final boolean shouldInstrumentPackages;

  public ShadowProviderGenerator(RobolectricModel model, ProcessingEnvironment environment,
                                 String shadowPackage, boolean shouldInstrumentPackages) {
    this.messager = environment.getMessager();
    this.elements = environment.getElementUtils();
    this.filer = environment.getFiler();
    this.model = model;
    this.shadowPackage = shadowPackage;
    this.shouldInstrumentPackages = shouldInstrumentPackages;
  }

  @Override
  public void generate() {
    if (shadowPackage == null) {
      return;
    }

    final String shadowClassName = shadowPackage + '.' + GEN_CLASS;

    // TODO: Because this was fairly simple to begin with I haven't
    // included a templating engine like Velocity but simply used
    // raw print() statements, in an effort to reduce the number of
    // dependencies that RAP has. However, if it gets too complicated
    // then using Velocity might be a good idea.
    PrintWriter writer = null;
    try {
      JavaFileObject jfo = filer.createSourceFile(shadowClassName);
      writer = new PrintWriter(jfo.openWriter());
      generate(writer);
    } catch (IOException e) {
      messager.printMessage(Diagnostic.Kind.ERROR, "Failed to write shadow class file: " + e);
      throw new RuntimeException(e);

    } finally {
      if (writer != null) {
        writer.close();
      }
    }
  }

  void generate(PrintWriter writer) {
    writer.print("package " + shadowPackage + ";\n");
    for (String name : model.getImports()) {
      writer.println("import " + name + ';');
    }
    writer.println();
    writer.println("/**");
    writer.println(" * Shadow mapper. Automatically generated by the Robolectric Annotation Processor.");
    writer.println(" */");
    writer.println("@Generated(\"" + RobolectricProcessor.class.getCanonicalName() + "\")");
    writer.println("@SuppressWarnings({\"unchecked\",\"deprecation\"})");
    writer.println("public class " + GEN_CLASS + " implements ShadowProvider {");

    final int shadowSize = model.getAllShadowTypes().size() + model.getExtraShadowTypes().size();
    writer.println("  private static final Map<String, String> SHADOW_MAP = new HashMap<>(" + shadowSize + ");");
    writer.println();

    writer.println("  static {");
    for (Map.Entry<TypeElement, TypeElement> entry : model.getAllShadowTypes().entrySet()) {
      final String shadow = elements.getBinaryName(entry.getKey()).toString();
      final String actual = entry.getValue().getQualifiedName().toString();
      writer.println("    SHADOW_MAP.put(\"" + actual + "\", \"" + shadow + "\");");
    }

    for (Map.Entry<String, String> entry : model.getExtraShadowTypes().entrySet()) {
      final String shadow = entry.getKey();
      final String actual = entry.getValue();
      writer.println("    SHADOW_MAP.put(\"" + actual + "\", \"" + shadow + "\");");
    }

    writer.println("  }");
    writer.println();

    for (Map.Entry<TypeElement, TypeElement> entry : model.getShadowOfMap().entrySet()) {
      final TypeElement shadowType = entry.getKey();
      final TypeElement actualType = entry.getValue();
      if (!actualType.getModifiers().contains(Modifier.PUBLIC)) {
        continue;
      }
      int paramCount = 0;
      StringBuilder paramDef = new StringBuilder("<");
      StringBuilder paramUse = new StringBuilder("<");
      for (TypeParameterElement typeParam : entry.getValue().getTypeParameters()) {
        if (paramCount > 0) {
          paramDef.append(',');
          paramUse.append(',');
        }
        boolean first = true;
        paramDef.append(typeParam);
        paramUse.append(typeParam);
        for (TypeMirror bound : model.getExplicitBounds(typeParam)) {
          if (first) {
            paramDef.append(" extends ");
            first = false;
          } else {
            paramDef.append(" & ");
          }
          paramDef.append(model.getReferentFor(bound));
        }
        paramCount++;
      }
      String paramDefStr = "";
      String paramUseStr = "";
      if (paramCount > 0) {
        paramDefStr = paramDef.append("> ").toString();
        paramUseStr = paramUse.append('>').toString();
      }
      final String actual = model.getReferentFor(actualType) + paramUseStr;
      final String shadow = model.getReferentFor(shadowType) + paramUseStr;
      if (shadowType.getAnnotation(Deprecated.class) != null) {
        writer.println("  @Deprecated");
      }
      writer.println("  public static " + paramDefStr + shadow + " shadowOf(" + actual + " actual) {");
      writer.println("    return (" + shadow + ") Shadow.extract(actual);");
      writer.println("  }");
      writer.println();
    }

    writer.println("  @Override");
    writer.println("  public void reset() {");
    for (Map.Entry<TypeElement, ExecutableElement> entry : model.getResetters().entrySet()) {
      Implements annotation = entry.getKey().getAnnotation(Implements.class);
      int minSdk = annotation.minSdk();
      int maxSdk = annotation.maxSdk();
      String ifClause;
      if (minSdk != -1 && maxSdk != -1) {
        ifClause = "if (org.robolectric.RuntimeEnvironment.getApiLevel() >= " + minSdk +
            " && org.robolectric.RuntimeEnvironment.getApiLevel() <= " + maxSdk + ") ";
      } else if (maxSdk != -1) {
        ifClause = "if (org.robolectric.RuntimeEnvironment.getApiLevel() <= " + maxSdk + ") ";
      } else if (minSdk != -1) {
        ifClause = "if (org.robolectric.RuntimeEnvironment.getApiLevel() >= " + minSdk + ") ";
      } else {
        ifClause = "";
      }
      writer.println("    " + ifClause + model.getReferentFor(entry.getKey()) + "." + entry.getValue().getSimpleName() + "();");
    }
    writer.println("  }");
    writer.println();

    writer.println("  @Override");
    writer.println("  public Map<String, String> getShadowMap() {");
    writer.println("    return SHADOW_MAP;");
    writer.println("  }");
    writer.println();

    writer.println("  @Override");
    writer.println("  public String[] getProvidedPackageNames() {");
    writer.println("    return new String[] {");
    if (shouldInstrumentPackages) {
      writer.println("      " + Joiner.on(",\n      ").join(model.getShadowedPackages()));
    }
    writer.println("    };");
    writer.println("  }");

    writer.println('}');
  }
}
