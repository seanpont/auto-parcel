package auto.parcel.processor;

import com.google.auto.common.MoreElements;
import com.google.auto.service.AutoService;
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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import auto.parcel.AutoAdapter;
import auto.parcel.AutoParcel;

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
    for (Element element : roundEnv.getElementsAnnotatedWith(AutoAdapter.class)) {
      if (element.getKind() != ElementKind.CLASS) {
        return error("@%s may only be used to annotate classes. Fix: %s", AutoAdapter.class.getSimpleName(), element.getSimpleName());
      }

      TypeElement typeElement = MoreElements.asType(element);
      if (!typeElement.getModifiers().contains(Modifier.PUBLIC)) {
        return error("%s must be public", typeElement.getSimpleName());
      }

      // ensure that it uses AutoParcel
      if (typeElement.getAnnotation(AutoParcel.class) == null) {
        return error("This can only be used with @AutoParcel classes");
      }

      // ensure that it has a builder method
      TargetBuilder builder = findBuilderMethod(typeElement);
      if (!findBuilderMethod(typeElement)) {
        return error("%s must have a builder method", typeElement.getSimpleName());
      }
      try {
        generateCode(typeElement);
      } catch (IOException e) {
        return error(e.getMessage());
      }
    }
    return false;
  }

  private void generateCode(TypeElement typeElement) throws IOException {
    String className = typeElement.getSimpleName() + "$Adapter";
    log("Generating adapter for %s", className);

    TypeName typeName = TypeName.get(typeElement.asType());
    TypeName adapterType = ParameterizedTypeName.get(ClassName.get(TypeAdapter.class), typeName);
    TypeSpec adapterClassSpec = TypeSpec.classBuilder(className)
        .superclass(adapterType)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addMethod(writeMethod(typeElement))
        .addMethod(readMethod(typeElement))
        .build();
    String packageName = MoreElements.getPackage(typeElement).getQualifiedName().toString();
    JavaFile javaFile = JavaFile.builder(packageName, adapterClassSpec).build();
    log("Package: %s", packageName);

    log(javaFile.toString());

    JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(className);
    Writer writer = sourceFile.openWriter();
    writer.write(javaFile.toString());
    writer.close();
    javaFile.writeTo(sourceFile.openWriter());
  }

  private MethodSpec writeMethod(TypeElement typeElement) {
    return MethodSpec.methodBuilder("write")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .addParameter(TypeName.get(JsonWriter.class), "out")
        .addParameter(TypeName.get(typeElement.asType()), "value")
        .addException(IOException.class)
        .addCode(writeMethodBody(typeElement))
        .build();
  }

  private CodeBlock writeMethodBody(TypeElement typeElement) {
    CodeBlock.Builder builder = CodeBlock.builder();
    builder.addStatement("out.beginObject()");
    for (Element element : typeElement.getEnclosedElements()) {
      if (element.getKind() == ElementKind.METHOD &&
          element.getModifiers().contains(Modifier.ABSTRACT)) {
        ExecutableElement executableElement = MoreElements.asExecutable(element);
        Name simpleName = executableElement.getSimpleName();
        log("out.name(\"%s\").value(value.%s())", simpleName, simpleName);
        builder.addStatement("out.name(\"%s\").value(value.%s())", simpleName, simpleName);
      }
    }
    builder.addStatement("out.endObject()");
    return builder.build();
  }

  private MethodSpec readMethod(TypeElement typeElement) {
    return MethodSpec.methodBuilder("read")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .addParameter(JsonReader.class, "in")
        .addException(IOException.class)
        .returns(TypeName.get(typeElement.asType()))
        .addCode(readMethodCode(typeElement))
        .build();
  }

  private CodeBlock readMethodCode(TypeElement typeElement) {
    CodeBlock.Builder builder = CodeBlock.builder();
    builder.addStatement()
    return builder.build();
  }

  static class TargetBuilder {
    ExecutableElement newBuilderMethod;
    TypeMirror builderClass;
    ExecutableElement buildMethod;
  }


  // A builder method will be a static no-arg method that returns a class that
  // has a no-arg method that returns an instance of this type
  private TargetBuilder findBuilderMethod(TypeElement typeElement) {
    for (Element element : typeElement.getEnclosedElements()) {
      if (element.getKind() == ElementKind.METHOD) {
        ExecutableElement newBuilderMethod = MoreElements.asExecutable(element);
        if (newBuilderMethod.getModifiers().contains(Modifier.STATIC)) {
          TypeMirror returnType = newBuilderMethod.getReturnType();
          if (returnType.getKind() == TypeKind.DECLARED) {
            DeclaredType builderClass = (DeclaredType) returnType;
            log("declared type: %s", builderClass);
            if (builderClass.getAnnotation(AutoParcel.Builder.class) == null) {
              log("%s not annotated with %s", builderClass, AutoParcel.Builder.class.getSimpleName());
              continue;
            }
            boolean b = findBuildMethod(builderClass, typeElement);
            if (b) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private boolean findBuildMethod(DeclaredType builderType, TypeElement returnType) {
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
              return true;
            }
          }
        }
      }
    }
    return false;
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
