
# Kotlin Scripting support

*Replaces [Script Definition Template](https://github.com/Kotlin/KEEP/blob/master/proposals/script-definition-template.md)
proposal.*

## Feedback

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/75)

## Motivation

- Define Kotlin scripting and its applications
- Describe intended use cases for the Kotlin scripting
- Define scripting support that is: 
  - applicable to all Kotlin platforms
  - provides sufficient control of interpretation and execution of scripts
  - simple enough to configure and customize
  - provides usable default components and configurations for the typical use cases    
- Provide basic examples of the scripting usage and implementation
- Address the issues found during the public usage of the current scripting support 

## Status of this document

The document is still a draft, and few important parts are still missing, in particular:

- REPL:
  - API for REPL host configuration and embedding
  - API for REPL plugins (maybe should be covered elsewhere):
    - custom repl commands
    - highlighting
    - completion
    - etc.
- IDE plugins API for custom scripting support (besides discovery)

## Table of contents

- [Applications](#applications)
- [Basic definitions](#basic-definitions)
- [Use cases](#use-cases)
  - [Embedded scripting](#embedded-scripting)
  - [Standalone scripting](#standalone-scripting)
  - [Project infrastructure scripting](#project-infrastructure-scripting)
  - [Script-based DSL](#script-based-dsl)
- [Proposal](#proposal)
  - [Architecture](#architecture)
  - [Script definition](#script-definition)
  - [Script Compilation](#script-compilation)
  - [Script Evaluation](#script-evaluation)
  - [Standard hosts and discovery](#standard-hosts-and-discovery)
  - [How to implement scripting support](#how-to-implement-scripting-support)
  - [Implementation status](#implementation-status)
  - [Examples](#examples)
    - [kotlin-main-kts](#kotlin-main-kts)

## Applications

- Build scripts (Gradle/Kobalt)
- Test scripts (Spek)
- Command-line utilities
- Routing scripts (ktor)
- Type-safe configuration files (TeamCity)
- In-process scripting and REPL for IDE
- Consoles like IPython/Jupyter Notebook
- Game scripting engines
- ...

## Basic definitions

- **Script** - a text file written in Kotlin language but allowing top-level statements and expressions and having access 
  to some implicit (not directly mentioned in the script text) properties, functions and objects, as if the whole script 
  body is a body of an implicit function placed in some environment (see below)
- **Scripting Host** - an application or a component which handles script execution   
- **Scripting Host Environment** - a set of parameters that defines an environment for all scripting host services,
  contains when relevant: project paths, jdk path, etc. It is passed on constructing the services     
- **REPL snippet** - a group of script text lines, executed in a single REPL eval call
- **Script compilation configuration** - a set of parameters configuring script compilation, such as dependencies,
  external variables declarations, implicit import statements, etc. 
- **Script evaluation configuration** - a set of parameters configuring script evaluation, such as external variables 
  instances, actual script parameters, etc. 
- **Script definition** - a set of parameters and configurations defining a script type   
- **Compiled script** - a binary compiled code of the script, stored in memory or on disk, which could be loaded 
  and instantiated by appropriate platform
- **Dependency** - an external library or another project whose declarations are available for the script being compiled 
  and evaluated
- **Imported script** - another script whose declarations are available for the script being compiled and evaluated
- **Configuration refinement** - the process of updating script compilation and evaluation configurations during 
  compilation/evaluation with parameter specific to the particular script exemplar, e.g. depending on the script 
  contents

## Use cases

### Embedded scripting

The use case when a scripting host is embedded into user's application, e.g. specialized console like 
IPython/Jupyter notebook, Spark shell, embedded game scripting, IDE and other application-level scripting. 

#### Environment and customization

In this case the script is most likely need to run in a specific execution environment, defined by the scripting host. 
The default script compilation and evaluation configurations are defined by the scripting host as well. The host may 
provide script authors with a possibility to customize some configuration parameters, e.g. to add dependencies or 
specify additional compilation options, e.g. using annotations in the script text:

```
@file:DependsOn("maven:artifact:1.0", "imported.package.*")
@file:Import("path/to/externalScript.kts")
@file:CompilerOptions("-someCompilerOpt")
```

To implement this the compilation/evaluation pipeline should provide a callback mechanism to call a host-provided code 
that could analyze script text directly or parsed annotations in order to update compilation and evaluation 
configurations with parameters depending on the script contents or other dynamic factors.

If the host need to support several script *types*, i.e. sets of compilation configurations and customization means, 
there should be a way for the host to distinguish the scripts and select appropriate set of 
compilation/configuration/evaluation services and properties. The host authors can implement the selection based on any 
script property, but due to the difficulties in supporting file type distinction based on anything but filename 
extension across platforms and IDEs, the default implementations should support only extension-based or file name based 
selection.     

*Note: It would be nice to provide an infrastructure (complete hosts or libraries) that support some typical mean for 
each platform to resolve external libraries from online repositories (e.g. - maven for JVM) out of the box.*

#### Typical usages

In a simple case, the developer wants to implement a scripting host to control script execution and provide the required 
environment. One may want to write something as simple as:
```
KotlinScriptingHost().eval(File("path/to/script.kts"))
```
or
```
KotlinScriptingHost().eval("println(\"Hello from script!\")")
```
and the script should be executed in the current environment with some reasonable default compilation and evaluation 
settings. If things need to be configured explicitly, the code would look like:
```
val scriptingHost = KotlinScriptingHost(configurationParams...)
scriptingHost.eval(File("path/to/script.kts"), compilationConfiguration, evaluationConfiguration)
```
This would also allow the developer to control the lifetime of the scripting host.

In cases then there is more control needed for the compilation and evaluation process, the direct use of the script
compiler and evaluator could be desirable:
```
val scriptCompiler = KotlinScriptCompiler(configurationParams...)
val compiledScript = scriptCompiler.invoke(File("path/to/script.kts), compilationConfiguration)
val scriptEvaluator = KotlinScriptEvaluator(configurationParams...)
val evaluationResult = scriptEvaluator.invoke(compiledScript, evaluationConfiguration)
```
In addition in this case the compiled script could be evaluated more than once.  

#### More control

To be able to run scripts in a user controlled environment, the following information bits could be configured or 
provided to the host: 
- *Script Compiler* - the service that will compile scripts into a form accepted by the Evaluator
- *Script Compilation Configuration* - set of properties defining the script compilation settings, including:
  - *Script Base Class* - an interface (or prototype) of the script class, expected by the executor, so the compiler
    should compile script into the appropriate class
  - *Dependencies* - external libraries that could be used in the script
  - *Default imports* - import statements implicitly added to any compiled script
  - *Provided properties* - properties with types that is assumed visible in the script scope
  - etc.
- *Script Evaluator* - the service that will actually evaluate the compiled scripts in the required execution 
  environment
- *Evaluation configuration* - set of properties defining an environment for the evaluation, including:
  - *Provided properties* - actual values of the provided properties from the compilation configuration
  - etc. 
  
Some of these parameters could be wrapped into a *Script Definition* for easier identification of the script types
by the hosts that may support handling of the several script types simultaneously. 
  
#### Caching

Since calling Kotlin compiler could be quite a heavy operation in comparison with typical script execution, the caching
of the compiled script should be supported by the compilation platform.

#### Execution lifecycle

The script is executed according to the following scheme:
- compilation - the *Script Compiler* takes the script, it's compilation configuration and 
  provides a compiled class. Inside this process. If the compilation configuration defines refinement callbacks, they 
  are called before or after parsing to update configuration with parameter depending on the script contents or other
  dynamic data.
- evaluation - the *Script Evaluator* takes the compiled script instantiates it if needed, and calls the appropriate 
  method, passing arguments from the environment to it; this step could be repeated many times. If evaluation 
  configuration defines refinement callbacks, they will be called before evaluation similarly to the compilation ones.
  
#### Processing in an IDE

The IDE support for the scripts should be based on the same *Script definition*. Basically after recognizing the script
*type* (see *Environment and customization* section above), the IDE extracts the *Compilation Configuration* and use its 
parameters to implement highlighting, navigation and other features. The default implementation of Kotlin IDEA plugin
should support the appropriate functionality, based on the standard set of configuration parameters.

### Standalone scripting

Standalone scripting applications include command-line utilities and a standalone Kotlin REPL.

Standalone scripting is a variant of the embedded scripting with hosts provided with the Kotlin distribution or 
3rd-party hosts.

#### Hosts

The standalone script could be executed e.g. using command line Kotlin compiler:

`kotlinc -cp <classpath required by the script definition> -script myscript.kts`

Or with the dedicated runner included into the distribution:

`kotlin -cp <classpath required by the script definition> myscrtipt.kts`

To be able to use the Kotlin scripts in a Unix shell environment, the *shebang* (`#!`) syntax should be supported 
at the beginning of the script:
```
#! /path/to/kotlin/script/runner -some -params
```

*Note: due to lack of clear specification, passing parameters in the shebang line could be 
[problematic](https://stackoverflow.com/questions/4303128/how-to-use-multiple-arguments-with-a-shebang-i-e/4304187#4304187), 
therefore alternative schemes of configuring scripts should be available.*

#### Script customizations

It should be possible to process custom scripts with the standard hosts, e.g. by supplying a custom script definition 
in the command line, e.g.:

`kotlin -script-templates="org.acme.MyScriptDef" -cp myScriptDefLib.jar myscript.myscr.kts`

In this case the host loads specified definition class, and extract required definition from it and its annotations.

Another possible mechanism is automatic discovery, with the simplified usage:

`kotlin -cp myScriptDefLib.jar myscript.myscr.kts`

In this case the host analyses the classpath, discovers script definitions located there and then processes then as 
before. Note that in this case it is should be recommended to use dedicated script extension (`myscr.kts`) in every 
definition to minimize chances of clashes if several script definitions will appear in the classpath. And on top of
that, some selection mechanism based on the file names and/or paths is needed, e.g. to cover cases like gradle's 
`build.gradle.kts` and `setting.gradle.kts` that should be treated like different script types.

#### Script parameters

For the command line usage the support for script parameters is needed. The simplest form is to assume that the script
has access to the `args: Array<String>` property/parameter. More advanced is to have a customization that supports a 
declaration of the typed parameters in the script annotations e.g.:

```
@file:param("name", "String?") // note: stringified types are used for the cases not supported by class literals
@file:param("num", Int::class)  
@file:param("list", "List<String>") 

// this script could be called with args "-name=abc -num=42 -list=a,b,c" 
// and then in the body we can access parsed typed arguments

println("${name ?: "<unknown>"} ${num/6}: ${list.map { it.toUpperCase() } }") 
```

#### IDE support

Since in this use case scripts are not part of any project, support for such script should be configured in the IDE
explicitly, either via a plugin or via the IDE settings.

#### Standalone REPL

Standalone REPL is invoked by a dedicated host the same way as for standalone script but accepts user's input as repl 
snippets. It means that the declarations made in the previous snippets are accessible in the subsequent ones.  
In this mode, all new scripting features should be accessible as well, including customization.

### Project infrastructure scripting

Applications: project-level REPL, build scripts, source generation scripts, etc.
 
Project infrastructure scripts are executed by some dedicated scripting host usually embedded into the project build 
system. So it is a variant of the embedded scripting with the host and the IDE support integrated into build system 
and/or IDE itself.

#### IDE support

From an IDE point of view, they are project-context dependent, but may not be part of the project sources. (In the same 
sense as e.g. gradle build scripts source is not considered as a part of the project sources.) In this case the support
in the IDE is possible only if the definitions are supplied to the IDE explicitly, similarly to standalone scripts,
either via a plugin or via the IDE settings.

#### Discovery

The IDE needs to be able to extract scripts environment configurations from the project settings, if the scrips are 
considered a part of the project sources, so the project's classpath could be used for discovery of the supported 
scripts.
 
#### Project-level REPL
 
A REPL that has access to the project's compiled classes.

### Script-based DSL

Applications: test definition scripts (Spek), routing scripts (ktor), type safe config files, etc.
 
In these cases the scripts are considered parts of the kotlin project and are compiled to appropriate binary form by the 
compiler, and then linked with the rest of the compilation results. They differ from the other project's sources by the 
possibility to employ script semantic and configurability and therefore avoid some boilerplate and make the sources 
look more DSL-like.
 
In this scenario, no dedicated scripting host is used, but the standard compiler is used during the regular compilation 
according to configured script recognition logic (e.g. the script type discovery mechanism described above), and the 
target application should implement its own logic for instantiating and calling the generated script classes.  
Script compiler may also annotate generated classes and methods with user-specified annotations to integrate it with 
existing execution logic. E.g. junit test scripts could be annotated accordingly.

From an IDE point of view, these scripts are the part of the project but should be configured according to the 
recognized script definition.

## Proposal

### Architecture

#### Components

The scripting support consists of the following components:
- `ScriptCompiler` - interface for script compilation
  - compilation: `(scriptSource, compilationConfiguration) -> compiledScript`
  - predefined script compilers based on the kotlin platforms: /JVM, /JS, /Native
  - custom/customized implementation possible
  - compiled scripts cashing belongs here
  - should not keep the state of the script compilation, the required state for the subsequent compilations, e.g. in the 
    REPL mode, is passed along with the compiled script
- `ScriptEvaluator` - the component that receives compiled script instantiates it, and then evaluates it in a required 
  environment, supplying any arguments that the script requires:
  - evaluation: `(compiledScript, evaluationConfiguration) -> Any?`
  - the `compiledScript` contains the final compilation configuration used
  - the `evaluationConfiguration` the parameters describing the actual script evaluation configuration of the script
  - predefined platform-specific evaluators available, but could be provided by the scripting host
  - pseudo-evaluators that save compiled script into e.g. an executable jar should be provided as well
- **IDE support** - Kotlin IDEA plugin should have support for scripting with script definition selection based on the 
  file name extension, and also includes discovery. The exposed generic ide support that would allow to build rich 
  script editing apps and REPLs is outside of the scope of this proposal and will be covered elsewhere.    
  
#### Data structures
  
- `ScriptSource` determines the way to access script for other components; it consists of:
  - the script reference pointer: url
  - an accessor to the script text  
  Both components are optional but at least one is required for a regular script.  
  The class implements source position and fragment referencing classes used e.g. in error reporting. 
- `KotlinType` a wrapper around Kotlin types, used to decouple script definition and compilation/evaluation 
  environments. It could be constructed either from reflected or from stringified type representation.
- `ScriptCompilationConfiguration` - a heterogeneous container of parameters defining the script compilation
- `ScriptEvaluationConfiguration` - a heterogeneous container of parameters defining the script evaluation 
- `ScriptingHostConfiguration` - a heterogeneous container of host-specific parameters  

### Script definition
 
Script Definition is a way to specify custom script. It is basically consists of a script base class annotated with 
`KotlinScript` annotation. The arguments of the annotation define the script configuration parameters.  For example: 

```
@KotlinScript(
    displayName = "My Script",
    fileExtension = "myscr.kts",
    compilationConfiguration = MyScriptCompilationConfiguration::class,
    evaluationConfiguration = MyScriptEvaluationConfiguration::class
)
abstract class MyScript(project: Project, val name: String) {
    fun helper1() { ... } 
    
    [@ScriptBody]
    [suspend] abstract fun <scriptBody>(params...): MyReturnType
}

object MyScriptCompilationConfiguration : ScriptCompilationConfiguration({
    defaultImports("java.io.*")
    providedProperties(prop1 to Int::class, prop2 to String::class)
})

object MyScriptEvaluationConfiguration : ScriptEvaluationConfiguration({
    providedProperties(prop1 to 42, prop2 to "foo")
})
```

*Where:*
- any valid method name could be used in place of `<scriptBody>` 
- `@ScriptBody` annotation marks the method the script body will be compiled into. In the absence of the explicit 
  annotation, if the configuration requires compilation into a method, the SAM notation will be used, otherwise
  the script will be generated into the target class constructor.
- `interface` or `open class` could be used in place of the `abstract class`
-  *(see also possible compilation configuration properties below)*

The annotations have reasonable defaults, so in the minimal case it is enough to mark the class only with the 
`@KoltinScript` without parameters. But it is recommended to give a dedicated file name extension for every script 
type to minimize chances for clashes in case of multiple definitions in one context.
The file name extension could be declared in the compilation configuration as well, but it is highly recommended
to define it in the annotation instead, since it will speed up the discovery process significantly.

### Script Compilation

#### Script Compiler

Script compiler implements the following interface:
```
interface ScriptCompiler {

    /**
     * Compiles the [script] according to the [scriptCompilationConfiguration]
     * @param script the interface to the script source code
     * @param scriptCompilationConfiguration the script compilation configuration properties
     * @return result wrapper, if successful - with compiled script
     */
    suspend operator fun invoke(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration
    ): ResultWithDiagnostics<CompiledScript<*>>
}
```
where:
```
interface CompiledScript<out ScriptBase : Any> {

    /**
     * The compilation configuration used for script compilation
     */
    val compilationConfiguration: ScriptCompilationConfiguration

    /**
     * The function that loads compiled script class
     * @param scriptEvaluationConfiguration the script evaluation configuration properties
     * @return result wrapper, if successful - with loaded KClass
     */
    suspend fun getClass(scriptEvaluationConfiguration: ScriptEvaluationConfiguration?): ResultWithDiagnostics<KClass<*>>

    /**
     * The name and the type of the script's result field, if any
     */
    val resultField: Pair<String, KotlinType>?
}

```
The compilers for the supported platforms are supplied by default scripting infrastructure.
  
#### Script Dynamic Compilation Configuration

The script compilation could be configured dynamically by specifying configuration refining callbacks in the compilation
configuration. The callback should be written according to the following signature: 
```
typealias RefineScriptCompilationConfigurationHandler =
            (ScriptConfigurationRefinementContext) -> ResultWithDiagnostics<ScriptCompilationConfiguration>
```
where:
```
class ScriptConfigurationRefinementContext(
    val script: SourceCode,
    val compilationConfiguration: ScriptCompilationConfiguration,
    val collectedData: ScriptCollectedData? = null
)
```
and `ScriptCollectedData` is a heterogeneous container of properties with the appropriate data collected during parsing.

See the following section for the parameters that declare such callbacks.

#### Compilation Configuration Properties

The following properties are recognized by the compiler:
- `baseClass` - target script superclass as well as a source for the script target method signature, constructor
  parameters and annotations, default - `Any`.
- `sourceFragments` - script fragments compile - allows to compile script partially
- `scriptBodyTarget` - defines whether script body will be compiled into resulting class constructor or to a 
  method body. In the latter case, there should be either single abstract method defined in the script base class, or
  single appropriate method should be annotated with the `ScriptBody` annotation
- `implicitReceivers` - a list of script types that is assumed to be implicit receivers for the script body, as
  if the script is wrapped into `with` expressions, in the order from outer to inner scope, i.e.:  
  ```
  with(receiver0) {
      ...
      with(receiverN) {
          <script body>
      }
  }
  ```
- `providedProperties` - a map (name -> type) of external variables visible for the script
- `defaultImports` - a list of import statements implicitly added to the script
- `restrictions` - a list of allow/deny rules containing qualified identifier wildcards, which are applied after
  resolving any identifier used in the script to determine whether a particular identifier should be accessible for 
  the script. This allows creating hosts with script functionality restrictions
- `importedScripts` - a list of scripts definitions from which should be available for the compiled script
- `dependencies` - a list of external libraries or modules available for the script
- `copyAnnotationsFrom` - an external class those annotations should be copied to the generated target class; if not
  specified, the annotations are copied from the base class (except for known scripting annotations)
- `compilerOptions` - a list of additional compiler options that should be passed to compiler on script compilation
- `resultField` - the name of the generated script class field to assign the script results to, empty means disabled, 
  default - `$$result`
- `refineConfigurationBeforeParsing` - a configuration refining callback that should be called before parsing is
  started
- `refineConfigurationOnAnnotations` - a list of script file-level annotations and configuration refining callback,
  if the specified annotations are found in the parsed script, the callback is called to get an updated configuration. 
- `refineConfigurationOnSections` - a list of top-level "sections" - function calls with single lambda parameter, e.g.  
  ```
  plugins {
      ...
  }
  ```  
  and the callback that should be called if specified sections are found in the parsed script
  
Additional properties are possible for particular platforms and compiler implementations.

#### Script class

In case of compiling into constructor, the script compiled into the following class

```
@CopiedAnnotation0(...)
...
@CopiedAnnotationN(...)
class Script(
    baseClassArguments..., 
    receiver0: ReceiverType0, ..., receiverN: ReceiverTypeN,
    val providedProperty0: ProvidedPropertyType0, ..., val providedPropertyN: ProvidedPropertyTypeN
): ScriptBaseClass(baseClassArguments...) {
    
    val val1: V1 by initOnce // for all vals/vars defined in the script body
    
    fun fn1(...): R1 {} // for all funcs defined in the script body
    
    class Cl1(...) {} // for all classes/objects defined in the script body
    
    val $$result: ResultType
    
    init {
        with(receiver0) {
            ...
            with(receiverN) {
                <script body>
                $$result = <last expression>
            }
        }
    }
}
```

In case of compiling into a method, the internal script properties are not exposed from the class:

```
@CopiedAnnotation0(...)
...
@CopiedAnnotationN(...)
class Script(
    baseClassArguments..., 
    receiver0: ReceiverType0, ..., receiverN: ReceiverTypeN,
    val providedProperty0: ProvidedPropertyType0, ..., val providedPropertyN: ProvidedPropertyTypeN
): ScriptBaseClass(baseClassArguments...) {
    
    [suspend] fun <scriptBody>(lambdaParams...): ReturnType {
        with(receiver0) {
            ...
            with(receiverN) {
                <script body>
            }
        }
    }}
```
 

The actual name of the `<scriptBody>` method is defined by the script base class.

The `implicitReceivers` may also contain compiled external scripts objects (from the `importedScripts` property).

*Note: The `with (implicitReceivers)` wrapping is needed in all methods and initializers defined in the class.*

### Script Evaluation

#### Script Evaluator

The script evaluator is an optional service (since the scripting host may choose to instantiate and execute compiled
scripts manually) for instantiating and running compiled scripts. The default evaluators for supported platforms 
are provided by the scripting infrastructure. It should implement the following interface:
```
interface ScriptEvaluator {
    suspend operator fun invoke(
        compiledScript: CompiledScript<*>,
        scriptEvaluationConfiguration: ScriptEvaluationConfiguration?
    ): ResultWithDiagnostics<EvaluationResult>
}
```

#### Evaluation properties

The following properties could be recognized by the evaluator, when passed in the `scriptEvaluationConfiguration` 
parameter:
- `implicitReceivers` - a list of actual implicit receivers objects, corresponding to the `implicitReceivers`
  parameter in the compilation configuration
- `providedProperties` - a map (name -> value) of the actual external variables visible to the script, corresponding to
  the `providedProperties` parameter in the compilation configuration
- `constructorArgs` - a list of constructor parameters corresponding to the script base class constructor, that should 
  be passed to the script constructor
- `runArgs` - a list of arguments to the script body method, if the `scriptBodyTarget` is set to the compiling script
  into the method
- `scriptsInstancesSharing` - boolean property defining the way of dealing with duplicates among imported scripts - if
  true - the imported scripts instances are kept in a container and shared among all use sites in the import hierarchy.
  (the helper `enableScriptsInstancesSharing()` provides another way of turning it on) 
- `refineConfigurationBeforeEvaluate` - a configuration refining callback that should be called before evaluation is
  started
  
It is possible to define and pass additional properties to a user-defined evaluator.    

#### Scripting host configuration 

These properties are defined by the scripting host and contain general parameters needed for all scripting services.
In particular, it is assumed that `ScriptCompilationConfigurator` and `ScriptEvaluater` implementations are instantiated
by passing the environment property bag to the appropriate constructors.  
The following parameters are defined:
- `configurationDependencies` - a list of dependencies required for script base class and services (configurator and
  evaluator), but not necessarily for scripts themselves (see below)
- `getScriptingClass` - an interface to an implementation-specific "class loader" for types specified in the 
  configurations
- `getEvaluationContext` - optional user-supplied function that can provide data to the evaluation configuration 
  refinement functions 
  
### Standard hosts and discovery

For standard JVM compiler, the command line parameter could be used to specify a script definition class name, and 
then regular compilation classpath will be used for dependencies.

Additionally, the automatic discovery of the templates marked by `@KotlinScript` annotation could be used with 
libraries that do not have plugins able to provide an extension. This could, for example, be used for test frameworks.  
In this case, the compiler scans all jars in the classpath for the discovery files - files in the folder 
`META-INF/kotlin/script/templates/` those names correspond to fully qualified names of the annotated script base 
classes. *(Note: the files' contents are not used, only the name)*. When it reads the annotations attached to these 
classes and lazily constructs actual script definitions from them.  

*(Note: the process is optimized in a way that if the file name extension could be extracted from the annotations alone,
the actual definition is not loaded until we will actually start to compile a script with the appropriate extension. 
This practically eliminates the overhead of script definitions discovery for projects that are not using scripts.)*

### How to implement scripting support
     
To implement scripting support on the JVM one should do the following

- Implement script definition library/module
   - define custom script compilation configuration class, inherit it from `ScriptCompilationConfiguration` and pass
     configuration lambda to the constructor of the latter
   - define custom script evaluation configuration class, inherit it from `ScriptEvaluationConfiguration` and pass
     configuration lambda to the constructor of the latter
   - define script base class with the `@KotlinScript` and pass at least file name extension and the configuration 
     classes to it
   - optionally add a discovery file named after FQN of the base class to the `META-INF/kotlin/script/templates/` in 
     the target jar 

If discovery file is added to the resulting jar, and this jar is added to the compilation classapth, it becomes possible 
to use custom script with the default hosts (e.g. Kotlin command-line compiler) and Kotlin IDEA plugin out of the box.   

*(Note for usage with Gradle: the discovery process is not turned on by default in the Kotlin gradle plugin, to enable
script definitions discovery in particular dependencies, they should be added to the `kotlinScriptDef` (or 
`testKotlinScriptDef`, etc.) configuration)*

Optionally the custom host and Idea support could be implemented:

- Implement the scripting host with the following functionality:
   - collect host configuration properties required for the services
   - instantiate compiler and evaluator
   - create basic host e.g. using platform-specific basic implementation   
   - in case of custom implementation, on each eval:
     - call compiler
     - call evaluator
      
- Implement IDE support via the appropriate extension
   - implement a class with `ScriptDefinitionsProvider` that returns appropriate definition class name and classpath
     needed to load it. 
   - use `org.jetbrains.kotlin.scriptDefinitionsProvider` extension point to register the provider class in IntelliJ
   - in the script compilation configuration created as described above, add `ide` section with the following properties:
     - `acceptedLocations` - the list of possible project locations where the script of this type will be recognized
       (see. `ScriptAcceptedLocation` enum class for the possible values)
     - `dependenciesSources` script source dependencies pointing to the jars with source code    

### Implementation status

The experimental implementation of the described scripting infrastructure on the JVM platform is a part of Kotlin 
starting from the 1.3.0 release. The following API and helper libraries are generally needed for the implementation:
- `kotlin-scripting-common` - API, interfaces, data structures and properties
- `kotlin-scripting-jvm` - JVM-specific properties and default implementations
- `kotlin-scripting-jvm-host` - JVM-specific host helpers

The basic examples could be found in the `libraries/samples/scripting` folder in the Kotlin source code repository.

The implementation is functional for many use cases, but there are many gaps, in particular:
- the following compilation configuration properties are not supported yet:
  - `sourceFragments` - scripts are only compiled as a whole for a moment; accordingly - the next point:
  - `refineConfigurationOnSections` - is not implemented
  - `scriptBodyTarget` - the generation into method body is not yet supported, so this parameter is ignored
  - `restrictions` - not implemented yet
  - `copyAnnotationsFrom` - is not implemented yet, the annotations are copied from the base class
- some properties are required by the current implementation are not part of this proposal, because they exist only 
  due to some implementation issues, and could soon disappear or change significantly, for example:
  - `getScriptingClass` in the environment - is used for kotlin types instantiation; currently the instance of
    `JvmGetScriptingClass` should be always used

The standard host functionality including discovery is implemented in the command-line compiler, gradle plugin and IDEA
plugin. But the following limitations are known:
  - if the script definition module is in the same project as the usage part, the module (and in particular - base 
    class) should be compiled to class files in order to be recognized by the discovery mechanism
  - the script compilation in maven project is not supported yet
  - the mechanisms for managing script definitions and resolving clashes are not implemented
  - additional configuration is needed to enable script definitions discovery in a gradle build (see note in the 
    [How to implement scripting support](#how-to-implement-scripting-support) section)
    
In general the scripting support is in the experimental stage, so we cannot guarantee stability of the interfaces and 
implementations.

### Examples

#### kotlin-main-kts
 
The `kotlin-main-kts` artifact contains a script definition and a minimal maven artifacts resolver in a single jar.
It allows to:
- create and use the simple utility scripts that may depend on the external libraries;
- extract the common functionality into “imported” scripts.

It could be used as an example as an advanced script definition or together with standard or custom hosts - as an
actual scripting tool. It implements JSR-223 host and therefore could be used via the `javax.script` API.

To extend scripts with functionality from 3rd-party libraries and to organize scripts structure, the `kotlin-main-kts`
implements support of the following file-level annotations:
- `@DependsOn` allows specifying library dependencies for the script. Either a direct path to the file or jar, or 
   maven coordinates are accepted as arguments.
- `@Repository` adds the maven repository for resolving dependencies. If none are specified explicitly, the maven central 
  repository is used.
- `@Import` allows importing another script into a given one. All the actions specified in the imported script
  run before all the actions specified in the given script and all the properties, functions and classes defined
  in the imported scripts become visible for the given script.

The artifact is distributed along with kotlin command line compiler package and published in Maven
Central as `org.jetbrains.kotlin:kotlin-main-kts`.

##### Usage

###### Example scripts

common.main.kts:
```
val greeting = "Hello, World!"
```

sample.main.kts:
```
@file:Repository("https://jcenter.bintray.com")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-html-jvm:0.6.11")
@file:Import("common.main.kts")

import kotlinx.html.*
import kotlinx.html.stream.*

print(createHTML().html {
    body {
        h1 { +greeting }
    }
})
```

###### Running from command-line compiler (script definition discovery)

```
kotlinc -cp <path/to/kotlin-main-kts.jar> -script sample.main.kts
```

###### Simple host: eval function

```
fun evalFile(scriptFile: File): ResultWithDiagnostics<EvaluationResult> {

    val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<MainKtsScript>()
    val evaluationConfiguration = createJvmEvaluationConfigurationFromTemplate<MainKtsScript>()

    return BasicJvmScriptingHost().eval(scriptFile.toScriptSource(), compilationConfiguration, evaluationConfiguration)
}

evalFile(File("sample.main.kts")
```

###### Using JSR-223 (`javax.script`) API

```
val engine = ScriptEngineManager().getEngineByExtension("main.kts")!!
engine.eval("""
@file:DependsOn("junit:junit:4.11")

org.junit.Assert.assertTrue(true)

println("Hello, World!")
""")
```
##### Implementation 

The complete source code could be found in the `libraries/tools/kotlin-main-kts` folder in the Kotlin source 
code repository. Here only partial sources are given to illustrate the main implementation points.

###### Script base class

```
@KotlinScript(
    fileExtension = "main.kts",
    compilationConfiguration = MainKtsScriptDefinition::class,
    evaluationConfiguration = MainKtsEvaluationConfiguration::class
)
abstract class MainKtsScript(val args: Array<String>)
```

###### Script compilation configuration properties

```
object MainKtsScriptDefinition : ScriptCompilationConfiguration(
    {
        defaultImports(DependsOn::class, Repository::class, Import::class)
        jvm {
            dependenciesFromClassContext( // extract dependencies from the host environment
                MainKtsScriptDefinition::class, // use this class classloader for dependencies search
                "kotlin-main-kts", "kotlin-stdlib", "kotlin-reflect" // search these libraries in it and use then as a script compilation classpath
            )
        }
        refineConfiguration {
            onAnnotations(
                DependsOn::class, Repository::class, Import::class, // if these annotations are found on script parsing 
                handler = MainKtsConfigurator() // call this handler to refine configuration parameters
            )
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere) // these scripts are recognized everywhere in the project structure
        }
        jsr223 {
            importAllBindings(true) // when used as a JSR-223 host, import engine bindings as kotlin properties
        }
    })
```

###### Script evaluation configuration properties

```
object MainKtsEvaluationConfiguration : ScriptEvaluationConfiguration(
    {
        scriptsInstancesSharing(true) // if a script is imported multiple times in the import hierarchy, use a single copy

        refineConfigurationBeforeEvaluate( // before evaluation, call this handler to refine configuration properties
            ::configureProvidedPropertiesFromJsr223Context
        )
    }
)
```

###### Compilation configuration refinement handler

```
class MainKtsConfigurator : RefineScriptCompilationConfigurationHandler {
    private val resolver = FilesAndIvyResolver()

    override operator fun invoke(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> =
        
        val annotations = context.collectedData?.get(ScriptCollectedData.foundAnnotations)?.takeIf { it.isNotEmpty() }
            ?: return context.compilationConfiguration.asSuccess()

        // collect imported scripts paths from  Import annotations 
        val scriptBaseDir = (context.script as? FileBasedScriptSource)?.file?.parentFile
        val importedSources = annotations.flatMap {
            (it as? Import)?.paths?.map { sourceName ->
                FileScriptSource(scriptBaseDir?.resolve(sourceName) ?: File(sourceName))
            } ?: emptyList()
        }

        // pass resolving annotations to resolver
        val resolvedClassPath = try {
            val scriptContents = object : ScriptContents {
                override val annotations: Iterable<Annotation> = annotations.filter { it is DependsOn || it is Repository }
                override val file: File? = null
                override val text: CharSequence? = null
            }
            resolver.resolve(scriptContents, emptyMap(), {}, null).get()?.classpath?.toList()
            // TODO: add diagnostics
        } catch (e: Throwable) {
            return ResultWithDiagnostics.Failure(*diagnostics.toTypedArray(), e.asDiagnostics(path = context.script.locationId))
        }

        // return updated configuration with resolved dependencies and imported scripts
        return ScriptCompilationConfiguration(context.compilationConfiguration) {
            if (resolvedClassPath != null) updateClasspath(resolvedClassPath)
            if (importedSources.isNotEmpty()) importScripts.append(importedSources)
        }.asSuccess()
    }
}
```

###### The discovery file (resources)
  
`META-INF/kotlin/script/templates/org.jetbrains.kotlin.mainKts.MainKtsScript.classname`

