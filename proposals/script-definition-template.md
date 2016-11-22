# Script Definition Template

Goal: flexibly defining script behaviour using Kotlin syntax and simple usage scheme.

## Feedback

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/28)

## Use cases

* Build scripts (Gradle/Kobalt)
* Test scripts (Spek)
* Command-line utilities
* Routing script for ktor (get(“/hello”) {...} on top level)
* Type-safe configuration files
* In-process scripting for IDE
* Consoles like IPython Notebook

## Proposal

Script definition templates are written as a regular class optionally annotated with specific annotations. For example:

```
@ScriptTemplateDefinition(
    resolver = GradleDependenciesResolver::class, // optional, could be used for additional dependencies resolution
    scriptFilePattern = "build.gradle.kts"        // optional, provides a pattern for script discovery in IDE and compiler
)
open class GradleScript(project: Project, val name: String) : Project by project {
    fun doSomething() { println(name) }
}
```

This class becomes a base class for generated script class.

Parameters of the primary constructor of the class describe the parameters of the script; varargs and default parameter values are allowed. Regular parameters (non-fields) become hidden in script body (e.g. in the example above `project` parameter is not accessible in the script body.)

Base dependencies of the script are equal to the dependencies  of the template class itself, provided via usage mechanism (see below). Additional dependencies and implicit imports are extracted by a classes passed to `@ScriptDependencyResolver` annotation, which should be then supplied along with template class.

## Usage

To use “templated” scripts in the compiler/IDE, the means to recognising and handling these scripts should be provided. It is enough to provide compiler/IDE with the fully qualified name of the class with a classpath containing all dependencies of that template class and resolvers specified in the `@ScriptTemplateDefinition` annotation. Alternatively the template class could be searched in the classpath by the `@ScriptTemplateDefinition` annotation.

In the user-controlled execution environment, e.g. in gradle, that could be achieved by creating special kind of `KotlinScriptDefinition` and passing it to the compiler, along with properly constructed `ClassLoader`.
In the IDE this could be implemented using the specific extension point:

```
interface ScriptTemplateProvider {
    val id: String // for resolving ambiguities (together with version field)
    val version: Int

    val isValid: Boolean // to simplify implementation of dynamic discovery

    val templateClassName: String
    val dependenciesClasspath: Iterable<File>
    
    val environment: Map<String, Any?>? // see Dependencies section for explanation
}
```


From the command line a parameter could be used to specify a template class name, and regular compilation classpath could be used for dependencies.

Additionally, the automatic discovery of the templates marked by `@ScriptTemplateDefinition` annotation could be used with libraries that do not have plugins able to provide an extension. This could for example be used for test frameworks.

## Script Files

To find the script definition corresponding to the script file, the compiler uses one of the following methods:

* Use the script definition specified explicitly in CompilerConfiguration (the Gradle case);
* Scan for all .jar in the classpath, load the list of script template definitions in each .jar from the JAR metadata,  and detect the applicable one based on the `@ScriptFilePattern` annotation;
* Use an explicit annotation referring to the FQ name of the script definition class:

```
@file:ScriptTemplate("org.jetbrains.kotlin.gradle.GradleScript")
```

## Dependencies

Dependencies required by the script are provided by the resolvers specified in parameters to `@ScriptTemplateDefinition` annotation on the template class. These are expected to implement the following interface:

```
interface KotlinScriptDependenciesResolver {
        @AcceptedAnnotations(...)  // allows to specify particular types of annotations accepted by the resolver
        fun resolve(script: ScriptContents,
                    environment: Map<String, Any?>?,
                    previousDependencies: KotlinScriptExternalDependencies?
        ): KotlinScriptExternalDependencies?
}
```

The method is called after script parsing in compiler and IDE and allows resolver to discover particular script dependencies using any annotations as well as to pass predefined dependencies for all scripts built with appropriate template. The parameters:

* script - the interface to the script file being processed defined as
    ```
    interface ScriptContents {
        val file: File?
        val annotations: Iterable<Annotation>
        val text: CharSequence?
    }
    ```
    where:
    * file - script file, if it is a file-based script
    * annotations - a list of file-targeted annotations from the script file filtered according to  `AcceptedAnnotations` annotation
    * text - an interface to the script contents
* environment - a map of entries representing environment specific for particular script template. The environment allows generally stateless resolver to extract dependencies according to the environment. E.g. for the gradle it could contain the gradle's `ProjectConnection` object used in the gradle IDEA plugin, allowing to reuse the project model already loaded into the plugin. The values are taken from the `ScriptTemplateProvider` extension point or script compilation call parameter. Could also contain a predefined set of parameters, e.g. “projectRootPath”
* previousDependencies - a value returned from the previous call to resolver, if any. It allows generally stateless resolver to implement an effective change detection logic, if the resolving is expensive

Returned `KotlinScriptExternalDependencies` is defined as:

```
interface KotlinScriptExternalDependencies {
    val javaHome: String? = null                      // JAVA_HOME path to use with the script
    val classpath: Iterable<File> get() = emptyList() // dependencies classpath
    val imports: Iterable<String> get() = emptyList() // implicit imports
    val sources: Iterable<File> get() = emptyList()   // dependencies sources for source navigation in IDE
    val scripts: Iterable<File> get() = emptyList()   // additional scripts to compile along with the current one
}
```

This schema allows user to implement any annotation-based syntax for dependency resolution, and if it is not enough, perform any parsing of the file directly.

## Further ideas to consider

* `ScriptTemplateProvider` extension point could be extended to provide dependencies changes notification mechanism to an IDE. That could help for example then dependencies are defined in a file other that script itself, so there is no way for IDE to detect the right moment to ask about changed dependencies.
* Additional possible annotations (or main annotation parameters) on the template class:
    * `ScriptDependencies` - for defining simple dependencies like JDK, kotlin stdlib or files with simple file searching scheme (e.g. from project's lib folder)
    * `ScriptDependenciesRepository` - for using with a “standard” dependency resolver, like maven, could be used together with a form of `ScriptDependencies` annotation accepting library coordinates.
    * `ScriptImplicitImports` - as a compliment to the direct dependencies specification, to allow specifying implicit imports directly

