package auto.parcel.processor;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.service.AutoService;
import com.google.common.collect.Lists;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import auto.parcel.AutoAdapter;

/**
 * Construct a JsonAdapter for the provided class
 */
@SuppressWarnings("UnusedDeclaration")
@AutoService(Processor.class)
public class AutoAdapterProcessor extends AbstractProcessor {

  @Override public Set<String> getSupportedAnnotationTypes() {
    return Collections.singleton(AutoAdapter.class.getName());
  }

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    log("process");
    for (Element element : roundEnv.getElementsAnnotatedWith(AutoAdapter.class)) {
      log("analyzing: " + element.getSimpleName());
      if (element.getKind() != ElementKind.CLASS) {
        return error("@%s may only be used to annotate classes, not %s",
            AutoAdapter.class.getSimpleName(), element.getSimpleName());
      }

      TypeElement typeElement = MoreElements.asType(element);
      log("%s is a class", typeElement.getSimpleName());
      if (!typeElement.getModifiers().contains(Modifier.PUBLIC)) {
        return error("%s must be public", typeElement.getSimpleName());
      }

      // collect necessary elements
      log("Creating TargetBuilder for %s", typeElement.getSimpleName());
      TargetBuilder targetBuilder = createTargetBuilder(typeElement);
      if (targetBuilder == null) {
        return error("Unable to generate TargetBuilder for %s", element.getSimpleName());
      }

      try {
        generateCode(targetBuilder);
      } catch (IOException e) {
        return error(e.getMessage());
      }
    }
    return false;
  }

  // ===== CODE GEN ================================================================================

  private void generateCode(TargetBuilder targetBuilder) throws IOException {
    final TypeElement targetClass = targetBuilder.targetClass;
    String className = "AutoAdapter_" + targetClass.getSimpleName();
    log("Generating adapter for %s", className);

    // generate superclass: TypeAdapter<TargetClass>
    TypeName typeName = TypeName.get(targetClass.asType());
    TypeName adapterType = ParameterizedTypeName.get(ClassName.get(TypeAdapter.class), typeName);
    TypeSpec adapterClassSpec = TypeSpec.classBuilder(className)
        .superclass(adapterType)
        .addJavadoc("AutoGenerated by AutoAdapter\n")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addMethod(writeMethod(targetBuilder))
        .addMethod(readMethod(targetBuilder))
        .build();
    String packageName = MoreElements.getPackage(targetClass).getQualifiedName().toString();
    JavaFile javaFile = JavaFile.builder(packageName, adapterClassSpec).build();

    log(javaFile.toString());

    JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(packageName + "." + className);
    Writer writer = sourceFile.openWriter();
    writer.write(javaFile.toString());
    writer.flush();
    writer.close();
  }

  private MethodSpec writeMethod(TargetBuilder targetBuilder) {
    return MethodSpec.methodBuilder("write")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .addParameter(TypeName.get(JsonWriter.class), "out")
        .addParameter(TypeName.get(targetBuilder.targetClass.asType()), "value")
        .addException(IOException.class)
        .addCode(writeMethodBody(targetBuilder))
        .build();
  }

  private CodeBlock writeMethodBody(TargetBuilder targetBuilder) {
    CodeBlock.Builder builder = CodeBlock.builder();
    builder.addStatement("out.beginObject()");
    for (Element element : targetBuilder.targetClassFields) {
      Name simpleName = element.getSimpleName();
      final String statement = String.format("out.name(\"%s\").value(value.%s())", simpleName, simpleName);
      builder.addStatement(statement);
    }
    builder.addStatement("out.endObject()");
    return builder.build();
  }

  private MethodSpec readMethod(TargetBuilder targetBuilder) {
    return MethodSpec.methodBuilder("read")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .addParameter(JsonReader.class, "in")
        .addException(IOException.class)
        .returns(TypeName.get(targetBuilder.targetClass.asType()))
        .addCode(readMethodCode(targetBuilder))
        .build();
  }

  private CodeBlock readMethodCode(TargetBuilder targetBuilder) {
    CodeBlock.Builder builder = CodeBlock.builder();
    // create builder object
    builder.addStatement("$T builder = $T.$N()",
        targetBuilder.builderClass,
        targetBuilder.targetClass,
        targetBuilder.newBuilderMethod.getSimpleName());
    builder.addStatement("in.beginObject()");
    builder.beginControlFlow("while (in.hasNext())");
    {
      builder.beginControlFlow("switch (in.nextName())");
      for (ExecutableElement method : targetBuilder.builderClassFields) {
        builder.add("case \"$N\":\n", method.getSimpleName());
        builder.indent();
        final String readerMethod = jsonReaderMethodFor(method);
        log("builder method: %s, jsonReaderMethod: %s", method, readerMethod);
        builder.addStatement("builder.$N($L)", method.getSimpleName(), readerMethod);
        builder.addStatement("break");
        builder.unindent();
      }
      builder.endControlFlow();
    }
    builder.endControlFlow();
    builder.addStatement("in.endObject()");
    builder.addStatement("return builder.$N()",
        targetBuilder.buildMethod.getSimpleName());
    return builder.build();
  }

  private String jsonReaderMethodFor(ExecutableElement method) {
    final TypeMirror type = method.getParameters().get(0).asType();
    log("%s: %s kind: %s", method, type, type.getKind());
    switch (type.getKind()) {
      case INT:
        return "in.nextInt()";
      case LONG:
        return "in.nextLong()";
      case FLOAT:
      case DOUBLE:
        return "in.nextDouble()";
      case BOOLEAN:
        return "in.nextBoolean()";
      case DECLARED:
        TypeElement typeElement = MoreElements.asType(MoreTypes.asElement(type));
        final String qualifiedName = typeElement.getQualifiedName().toString();
        log("qualified name: %s", qualifiedName);
        if (qualifiedName.equals(String.class.getCanonicalName())) {
          return "in.nextString()";
        } else {
          return String.format("new %s(in.nextString())", qualifiedName);
        }
      default:
        throw new RuntimeException("Unknown type");
    }
  }

  // ===== CODE INSPECTOR ==========================================================================

  static class TargetBuilder {
    TypeElement targetClass;
    List<ExecutableElement> targetClassFields = Lists.newArrayList();
    ExecutableElement newBuilderMethod;
    TypeElement builderClass;
    List<ExecutableElement> builderClassFields = Lists.newArrayList();
    ExecutableElement buildMethod;
  }

  /**
   * TargetBuilder contains the Target class, the method that constructs the builder, the builder's type,
   * and the build method within the builder class.
   * The builder method must be static and must return a class that has a no-arg method that returns an instance
   * of the target class.
   * ie:
   * \@AutoParcel
   * \@AutoAdapter                    <= required
   * \@JsonAdapter(AutoAdapter_Target.class)
   * class Target {                   <= targetClass
   *
   *   public abstract int foo();     <= targetClassField[0]
   *   public abstract String bar();  <= targetClassField[1]
   *   public String blah();          <= targetClassField[2]
   *   public long getWhatever();     <= targetClassField[3]
   *
   *   static Builder builder()       <= newBuilderMethod
   *
   *   \@AutoParcel.Builder
   *   public interface Builder {     <= builderClass
   *     Builder foo(int foo);        <= builderClassField[0]
   *     Builder bar(String bar);     <= builderClassField[1]
   *     Target build();              <= buildMethod
   *   }
   * }
   */
  private TargetBuilder createTargetBuilder(TypeElement typeElement) {
    TargetBuilder targetBuilder = new TargetBuilder();
    targetBuilder.targetClass = typeElement;
    targetBuilder.targetClassFields = collectClassFields(typeElement);
    log("%s fields: %s", targetBuilder.targetClass.getSimpleName(), targetBuilder.targetClassFields);
    findBuilder(targetBuilder);
    if (targetBuilder.builderClass == null) {
      log("unable to find builder class");
      return null;
    }
    targetBuilder.builderClassFields = collectBuilderClassFields(targetBuilder.builderClass);
    log("builder methods: %s", targetBuilder.builderClassFields);
    return targetBuilder;
  }

  /**
   * All non-static methods that take one param and return a builder
   */
  private List<ExecutableElement> collectBuilderClassFields(TypeElement builderClass) {
    List<ExecutableElement> methods = Lists.newArrayList();
    for (Element element : builderClass.getEnclosedElements()) {
      if (element.getKind() == ElementKind.METHOD &&
          !element.getModifiers().contains(Modifier.STATIC)) {
        final ExecutableElement executableElement = MoreElements.asExecutable(element);
        if (executableElement.getParameters().size() == 1) {
          methods.add(executableElement);
        }
      }
    }
    return methods;
  }

  private void findBuilder(TargetBuilder targetBuilder) {
    // Find static builder method and builder class
    for (Element element : targetBuilder.targetClass.getEnclosedElements()) {
      // must be a static method with no args and a class return type
      if (element.getKind() != ElementKind.METHOD) continue;
      if (!element.getModifiers().contains(Modifier.STATIC)) continue;

      final ExecutableElement newBuilderMethod = MoreElements.asExecutable(element);
      if (newBuilderMethod.getParameters().size() > 0) continue;
      final TypeMirror builderMethodReturnType = newBuilderMethod.getReturnType();
      if (builderMethodReturnType.getKind() != TypeKind.DECLARED) continue;
      log("potential builder method: %s", newBuilderMethod);
      TypeElement builderClass = (TypeElement) ((DeclaredType) builderMethodReturnType).asElement();
      log("potential builder class: %s", builderClass.getSimpleName());

      for (Element builderElement : builderClass.getEnclosedElements()) {
        if (builderElement.getKind() != ElementKind.METHOD) continue;
        final ExecutableElement buildMethod = MoreElements.asExecutable(builderElement);
        if (buildMethod.getParameters().size() == 0) {
          // could be build method
          if (buildMethod.getReturnType().getKind() == TypeKind.DECLARED) {
            log("potential build method: %s", buildMethod);
            final TypeMirror buildMethodReturnType = buildMethod.getReturnType();
            final Element buildMethodReturnElement = processingEnv.getTypeUtils().asElement(buildMethodReturnType);
            if (buildMethodReturnElement.equals(targetBuilder.targetClass)) {
              log("found build method: %s", buildMethod);
              targetBuilder.newBuilderMethod = newBuilderMethod;
              targetBuilder.builderClass = builderClass;
              targetBuilder.buildMethod = buildMethod;
            }
          }
        }
      }
    }
  }

  private List<ExecutableElement> collectClassFields(TypeElement typeElement) {
    // Collect fields
    List<ExecutableElement> fields = Lists.newArrayList();
    ElementFilter.fieldsIn(typeElement.getEnclosedElements());
    for (Element element : typeElement.getEnclosedElements()) {
      if (element.getKind() == ElementKind.METHOD) {
        final ExecutableElement executableElement = MoreElements.asExecutable(element);
        // must be public non-static with a return type
        final Set<Modifier> modifiers = executableElement.getModifiers();
        if (modifiers.contains(Modifier.PUBLIC) &&
            !modifiers.contains(Modifier.STATIC) &&
            !(executableElement.getReturnType() instanceof NoType)) {
          fields.add(executableElement);
        }
      }
    }
    return fields;
  }

  private ExecutableElement findBuildMethod(DeclaredType builderType, TypeElement returnType) {
    for (Element element : builderType.asElement().getEnclosedElements()) {
      if (element.getKind() == ElementKind.METHOD) {
        ExecutableElement executableElement = MoreElements.asExecutable(element);
        if (executableElement.getParameters().size() == 0 &&
            !executableElement.getModifiers().contains(Modifier.STATIC) &&
            executableElement.getReturnType().getKind() == TypeKind.DECLARED) {
          if (returnType.getKind() == ElementKind.CLASS) {
            boolean equals = returnType.getQualifiedName().equals(returnType.getQualifiedName());
            log("return types equal? %s", equals);
            if (equals) {
              return executableElement;
            }
          }
        }
      }
    }
    return null;
  }

  private void log(String format, Object... args) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
        "@AutoAdapter log: " + String.format(format, args));
  }

  private boolean error(String format, Object... args) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
        "@AutoAdapter error: " + String.format(format, args));
    return true;
  }
}
