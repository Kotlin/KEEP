
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
- ?         

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

- **Script** - a text file written in Kotlin language but allowing top-level statements and having access to some 
  implicit (not directly mentioned in the script text) properties, functions and objects, as if the whole script body 
  is a body of an implicit function placed in some environment (see below)
- **Scripting Host** - an application or a component which handles script execution   
- **Scripting Host Environment** - a set of parameters that defines an environment for all scripting host services,
  contains when relevant: project paths, jdk path, etc. It is passed on constructing the services     
- **REPL statement** - a group of script text lines, executed in a single REPL eval call
- **Script compilation configuration** - a set of parameters configuring a script compilation, such as dependencies,
  external (global) variables, script parameters, etc. 
- **Script definition** - a set of parameters and services defining a script type   
- **Compiled script** - a binary compiled code of the script, stored in memory or on disk, which could be loaded 
  and instantiated by appropriate platform
- **Dependency** - an external library or another project whose declarations are available for the script being compiled 
  and evaluated
- **Imported script** - another script whose declarations are available for the script being compiled and evaluated
- **Execution environment** - the environment in which the script is executed, defining which services, objects, 
  actions, etc. are accessible for the script

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
@file:dependsOn("maven:artifact:1.0", "imported.package.*")
@file:require("path/to/externalScript.kts")
@file:compilerOptions("-someCompilerOpt")
```

If the host need to support several script *types*, i.e. sets of compilation configurations and customization means, 
there should be a way for the host to distinguish the scripts and select appropriate set of 
compilation/configuration/evaluation services and properties. The host authors can implement the selection based on any 
script property, but due to the difficulties in supporting file type distinction based on anything but filename 
extension across platforms and IDEs, the default implementations should support only extension-based selection.     

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
scriptingHost.eval(File("path/to/script.kts"))
```
This would also allows the developer to control the lifetime of the scripting host.

In some specific cases it is desired to perform additional action between compilation and actual evaluation of the 
script, e.g. verify the compiled script. In this case, the usage may look like:
```
val scriptingHost = KotlinScriptingHost(configurationParams...)
val compiledScript = scriptingHost.compile(File("path/to/script.kts"))
// do some verification, and if it succeeds
scriptingHost(compiledScript, evaluationParams...)
```
This snipped provides also an example of passing specific parameters to the evaluation, which should be supported for 
the simpler usages as well.  
And also in this form the compiled script could be evaluated more than once.  

#### More control

To be able to run scripts in a user controlled environment, the following information bits could be configured or 
provided to the host: 
- *Script Compiler* - the service that will compile scripts into a form accepted by the Evaluator
- *Script Compilation Configuration* - set of properties defining the script compilation settings, including:
  - *Script Base Class* - an interface (or prototype) of the script class, expected by the executor, so the compiler
    should compile script into the appropriate class
  - *Dependencies* - external libraries that could be used in the script
  - *Default imports* - import statements implicitly added to any compiled script
  - *Environment variables* - global variables with types that is assumed visible in the script scope
  - etc.
- *Script Compilation Configurator* - the service that provides a dynamic part of the configuration for a script 
  compilation, so the part dependent on the compiled script content.
- *Script Evaluator* - the service that will actually evaluate the compiled scripts in the required execution 
  environment
- *Evaluation environment* - set of properties defining an environment for the evaluation, including:
  - *Environment variables* - actual values of the environment variables from compilation configuration
  - etc. 
  
Some of these parameters could be wrapped into a *Script Definition* for easier identification of the script types
by the hosts that may support handling of the several script types simultaneously. 
  
#### Caching

Since calling Kotlin compiler could be quite a heavy operation in comparison with typical script execution, the caching
of the compiled script should be supported by the compilation platform.

#### Execution lifecycle

The script is executed according to the following scheme:
- compilation - the *Script Compiler* takes the script, it's configurator and/or configuration and 
  provides a compiled class. Inside this process, the following activity is possible:
  - configuration refinement - if the compilation configured accordingly, the configurator is called after parsing to 
    refine the configuration taking into account the script contents:
- evaluation - the *Evaluator* takes the compiled script instantiates it if needed, and calls the appropriate method, 
  passing arguments from the environment to it; this step could be repeated many times
  
#### Processing in an IDE

The IDE support for the scripts should be based on the same *Script definition*. Basically after recognizing the script
*type* (see *Environment and customization* section above), the IDE may use the *Compilation Configuration* and use its 
parameters to implement highlighting, navigation and other features. The default implementation of Kotlin IDEA plugin w
should support the appropriate functionality, based on the standard set of configuration parameters.

### Standalone scripting

Standalone scripting applications include command-line utilities and a standalone Kotlin REPL.

Standalone scripting is a variant of the embedded scripting with hosts provided with the Kotlin distribution.

#### Hosts

The standalone script could be executed e.g. using command line Kotlin compiler:

`kotlinc -script myscript.kts`

Or with the dedicated runner included into the distribution:

`kotlin myscrtipt.kts`

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

`kotlin -scriptDefinition="org.acme.MyScriptDef" -scriptDefinitionClasspath=myScriptDefLib.jar myscript.kts`

In this case the host loads specified definition class, and extract required definition from it and its annotations.

Another possible mechanism is automatic discovery, with the simplified usage:

`kotlin -cp=myScriptDefLib.jar myscript.myscr.kts`

In this case the host analyses the classpath, discovers script definitions located there and then processes then as 
before. Note that in this case it is should be recommended to use dedicated script extension (`myscr.kts`) in every 
definition to minimize chances of clashes if several script definitions will appear in the classpath. And on top of
that, some clash-resolving mechanism is needed.   

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

Since in this use case scripts are not part of any project, and content-based script type detection appears quite 
difficult, the possibility of reasonable IDE support is questionable.

#### Standalone REPL

Standalone REPL is invoked by a dedicated host the same way as for standalone script but accepts user's input as repl 
statements. It means that the declarations made in the previous statements are accessible in the subsequent ones.  
In this mode, all new scripting features should be accessible as well, including customization.

### Project infrastructure scripting

Applications: project-level REPL, build scripts, source generation scripts, etc.
 
Project infrastructure scripts are executed by some dedicated scripting host usually embedded into the project build 
system. So it is a variant of the embedded scripting with the host and the IDE support integrated into build system 
and/or IDE itself.

#### IDE support

From an IDE point of view, they are project-context dependent, but may not be part of the project sources. (In the same 
sense as e.g. gradle build scripts source is not considered as a part of the project sources.)

#### Discovery

The IDE needs to be able to extract scripts environment configurations from the project settings.
 
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
- **ScriptCompiler** - interface for script compilation
  - compilation: `(scriptSource, compilationConfigurator, additionalCompilationConfiguration) -> compiledScript`
  - predefined script compilers based on the kotlin platforms: /JVM, /JS, /Native
  - custom/customized implementation possible
  - compiled scripts cashing belongs here
  - should not keep the state of the script compilation, the required state for the subsequent compilations, e.g. in the 
    REPL mode, is passed along with the compiled script
- **ScriptCompilationConfigurator** - provides *static* and content-dependent configuration properties for the compiler
  - some basic implementations are provided, but for custom scripting user may provide an implementation  
  - `defaultConfiguration` property of type `ScriptCompileConfiguration`
  - `refineConfiguration(scriptSource, configuration, processedScriptData) -> ScriptCompileConfiguration`
  - the `refineConfiguration` configuration is called only if the *static* configuration has specific *refine request*
    properties, and the passed `processedScriptData` contains appropriate data extracted from parsing. E.g. if the
    *static* configuration contains a property `refineConfigurationOnAnnotations`, the `refineConfiguration` will be 
    called after parsing and the `processedScriptData` parameter will contain a list of parsed annotations.
- **ScriptEvaluator** - the component that receives compiled script instantiates it, and then evaluates it in a required 
  environment, supplying any arguments that the script requires:
  - evaluation: `(compiledScript, environment) -> Any?`
  - the `compiledScript` contains the final compilation configuration used
  - the `environment` is an entity denoting/referencing the actual execution environment of the script
  - predefined platform-specific executors available, but could be provided by the scripting host
  - possible executors
    - JSR-223
    - IDEA REPL
    - Jupyter
    - Gradle
    - with specific coroutines context
    - ...
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
- `ChainedPropertyBag` - a heterogeneous container for typed properties, that could be chained with a "parent"
  container, which is being searched for a property, if it is not found in the current one. This allow to implement 
  properties overriding mechanisms without copying, e.g. for configuration refining. Used for all relevant properties, 
  like compilation configuration and evaluation environment.
- `ScriptDefinition` - a facade combining services and properties defining a script type. Could be constructed manually
  or from annotated script base class.

### Script definition

Script Definition is a facade combining services and properties in one entity for simplified discovery and 
configuration of a script type. It also defines the base class for the generated script classes (in the properties), 
and therefore defines the script "skeleton" and DSL.
```
interface ScriptDefinition {

    // constructor(environment: ScriptingEnvironment) // the constructor is expected from implementations

    val properties: ScriptDefinitionPropertiesBag

    val compilationConfigurator: ScriptCompilationConfigurator

    val evaluator: ScriptEvaluator<*>?
}
```

#### Declaring script definition

The definition could be constructed manually, but the most convenient way, which is also supported by the discovery
mechanism, is to annotate the appropriate script base class/interface with the script defining annotations and then
allow provided scripting infrastructure to construct the definition from it. For example: 

```
@KotlinScript("My script")
@KotlinScriptFileExtension("myscr.kts")
@KotlinScriptCompilationConfigurator(MyConfigurator::class)
@KotlinScriptEvaluator(MyEvaluator::class)
abstract class MyScript(project: Project, val name: String) {
    fun helper1() { ... } 
    
    [@ScriptBody]
    [suspend] abstract fun <scriptBody>(params...): R
}
```

*Where:*
- any valid method name could be used in place of `<scriptBody>` 
- `@ScriptBody` annotation marks the method the script body will be compiled into. In the absence of the explicit 
  annotation the SAM notation will be used
- `interface` or `open class` could be used in place of the `abstract class`

The annotations have reasonable defaults, so in the minimal case it is enough to mark the class only with the 
`@KoltinScript` without parameters. But it is recommended to give a dedicated file name extension for every script 
type to minimize chances for clashes in case of multiple definitions in one context.

#### Static compilation configuration

In cases then script compilation configuration is static, instead of defining custom `ScriptCompilationConfigurator` 
it is possible to declare configuration statically in an object or class implementing the `List` interface holding
configuration properties, and use appropriate annotation that will instruct the scripting infrastructure to create 
a configurator from these properties. E.g.:

```
object MyScriptConfiguration : List<Pair<TypedKey<*>, Any?>> by ArrayList<Pair<TypedKey<*>, Any?>>(
    listOf(
        ScriptCompileConfigurationProperties.defaultImports to listOf("java.io.*")
    )
)

@KotlinScript
@KotlinScriptFileExtension("myscr.kts")
@KotlinScriptDefaultCompilationConfiguration(MyScriptConfiguration::class)
interface MyScript
```

*(see possible compilation configuration properties below)*

### Script Compilation

#### Script Compiler

Script compiler implements the following interface:
```
interface ScriptCompiler {

    suspend fun compile(
        script: ScriptSource,
        configurator: ScriptCompilationConfigurator? = null,
        additionalConfiguration: ScriptCompileConfiguration? = null // overrides parameters from configurator.defaultConfiguration
    ): ResultWithDiagnostics<CompiledScript<*>>
}
```
The compilers for the supported platforms are supplied by default scripting infrastructure.

#### Script Compilation Configurator

The Script Compilation Configurator is a class implemented by the script definition author, or provided by the 
infrastructure from the author-supplied static configuration properties. It should implement the following interface:
```
interface ScriptCompilationConfigurator {

    // constructor(environment: ScriptingEnvironment) // the constructor is expected from implementations

    val defaultConfiguration: ScriptCompileConfiguration

    suspend fun refineConfiguration(
        scriptSource: ScriptSource,
        configuration: ScriptCompileConfiguration,
        processedScriptData: ProcessedScriptData = ProcessedScriptData()
    ): ResultWithDiagnostics<ScriptCompileConfiguration> =
        defaultConfiguration.asSuccess()
}
```
  
#### Compilation Configuration Properties

The following properties are recognized by the compiler:
- `sourceFragments` - script fragments compile - allows to compile script partially
- `scriptBodyTarget` - defines whether script body will be compiled into resulting class constructor or to a 
  method body. In the latter case, there should be either single abstract method defined in the script base class, or
  single appropriate method should be annotated accordingly (*TODO:* elaborate)
- `scriptImplicitReceivers` - a list of script types that is assumed to be implicit receivers for the script body, as
  if the script is wrapped into `with` statements, in the order from outer to inner scope, i.e.:  
  ```
  with(receivers[0]) {
      ...
      with(receivers.last()) {
          <script body>
      }
  }
  ```
- `contextVariables` - a map (name -> type) of external variables visible for the script
- `defaultImports` - a list of import statements implicitly added to the script
- `restrictions` - a list of allow/deny rules containing qualified identifier wildcards, which are applied after
  resolving any identifier used in the script to determine whether a particular identifier should be accessible for 
  the script. This allows creating hosts with script functionality restrictions
- `importedScripts` - a list of scripts definitions from which should be available for the compiled script
- `dependencies` - a list of external libraries or modules available for the script
- `generatedClassAnnotations` - a list of runtime annotations that should be attached the the generated script class
- `generatedMethodAnnotations` - a list of runtime annotation that should be attached to the generated script body 
  method. These two properties could be used e.g. for generating tests.
- `compilerOptions` - a list of additional compiler options that should be passed to compiler on script compilation
- `refineBeforeParsing` - a flag asking to call `ScriptCompilationConfigurator.refineConfiguration` before parsing
  the script
- `refineConfigurationOnAnnotations` - a list of script file-level annotations, which should trigger calling 
  `ScriptCompilationConfigurator.refineConfiguration` after parsing
- `refineConfigurationOnSections` - a list of top-level "sections" - function calls with single lambda parameter, e.g.  
  ```
  plugins {
      ...
  }
  ```  
  which should trigger calling `ScriptCompilationConfigurator.refineConfiguration` after parsing
  
Additional properties are possible for particular platforms and compiler implementations.  

#### Processed script properties on configuration refinement

When configuration refinement is called from the compiler, the following properties could be passed by compiler in the
`processedScriptData` argument to the `refineConfiguration` call:
- `foundAnnotations` - a list of actual `Annotation` objects, from the list of annotations requested by the 
  `refineConfigurationOnAnnotations` property, found in the script being compiled
- `refineConfigurationOnAnnotations` - a list of actual named source fragments/sections, from the list of ones requested
  by the `refineConfigurationOnSections` property, found in the script being compiled
  
#### Script class

Script compiled into the following class

```
class Script(
    baseClassArguments..., 
    implicitReceivers: Array<Any>, 
    contextVariables: Map<String, Any?>
): ScriptBaseClass(baseClassArguments...) {
    
    val val1: V1 by initOnce // for all vals/vars defined in the script body
    
    fun fn1(...): R1 {} // for all funcs defined in the script body
    
    class Cl1(...) {} // for all classes/objects defined in the script body
    
    var contextVar1: CVT1 = contextVariables["contextVar1"] // for all contextVariables, the types are defined by `contextVariables` parameter in configuration 
    
    [suspend] fun <scriptBody>(lambdaParams...): RetVal {
        with(implicitReceivers) {
            ...
        }
    }
    
    // or, depending on the scriptBodyTarget configuration property
    init {
        with(implicitReceivers) {
            ...
        }
    }
}
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
interface ScriptEvaluator<in ScriptBase : Any> {

    // constructor(environment: ScriptingEnvironment) // the constructor is expected from implementations

    suspend fun eval(
        compiledScript: CompiledScript<ScriptBase>,
        scriptEvaluationEnvironment: ScriptEvaluationEnvironment
    ): ResultWithDiagnostics<EvaluationResult>
}
```

#### Evaluation properties

The following properties could be recognized by the evaluator, when passed in the `scriptEvaluationEnvironment` 
parameter:
- `implicitReceivers` - a list of actual implicit receivers objects, corresponding to the `scriptImplicitReceivers`
  parameter in the compilation configuration
- `contextVariables` - a map (name -> value) of the actual external variables visible to the script, corresponding to
  the `contextVariables` parameter in the compilation configuration
- `constructorArgs` - a list of constructor parameters corresponding to the script base class constructor, that should 
  be passed to the script constructor
- `runArgs` - a list of arguments to the script body method, if the `scriptBodyTarget` is set to the compiling script
  into the method
  
It is possible to define and pass additional properties to a user-defined evaluator.    

#### Scripting environment properties

These properties are defined by the scripting host and contain general parameters needed for all scripting services.
In particular, it is assumed that `ScriptCompilationConfigurator` and `ScriptEvaluater` implementations are instantiated
by passing the environment property bag to the appropriate constructors.  
The following parameters are defined:
- `configurationDependencies` - a list of dependencies required for script base class and services (configurator and
  evaluator), but not necessarily for scripts themselves (see below)
- `isolatedDependencies` - a flag denoting whether the dependencies listed above should be visible to the scripts
  themselves
  
### Standard hosts and discovery

For standard JVM compiler, the command line parameter could be used to specify a script definition class name, and 
regular compilation classpath will be used for dependencies.

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
   - implement script compilation configurator or static configuration properties list
   - optionally implement script evaluator
   - define script base class with the `@KotlinScript` and other defining annotations
   - optionally add a discovery file named after FQN of the base class to the `META-INF/kotlin/script/templates/` in 
     the target jar 

If discovery file is added to the resulting jar, and this jar is added to the compilation classapth, it becomes possible 
to use custom script with the default hosts (e.g. Kotlin command-line compiler) and Kotlin IDEA plugin out of the box.   
Optionally the custom host and Idea support could be implemented:

- Implement the scripting host with the following functionality:
   - collect environment properties required for the services
   - instantiate compiler, configurator and evaluator
   - create basic host e.g. using platform-specific basic implementation   
   - in case of custom implementation, on each eval:
     - call compiler
     - call evaluator
      
- Implement IDE support vie appropriate extension
   - *TODO: current extension mechanism is outdated and needs to be adapted to the new scripting infrastructure*

### Implementation status

The experimental implementation of the described scripting infrastructure on the JVM platform is a part of Kotlin 
starting from the 1.2.50 release. The following API and helper libraries are generally needed for the implementation:
- `kotlin-scripting-common` - API, interfaces, data structures and properties
- `kotlin-scripting-jvm` - JVM-specific properties and default implementations
- `kotlin-scripting-jv-host` - JVM-specific host helpers
- `kotlin-scripting-common` - an experimental DSL for properties

The basic examples could be found in the `libraries/samples/scripting` folder in the Kotlin source code repository.

The implementation is functional for many use cases, but there are many gaps, in particular:
- the following compilation configuration properties are not supported yet:
  - `sourceFragments` - scripts are only compiled as a whole for a moment; accordingly - the next point:
  - `refineConfigurationOnSections` - has no effect now
  - `scriptBodyTarget` - the generation into method body is not yet supported, so this parameter is ignored
  - `restrictions` - not implemented yet
  - `importedScripts` - not implemented yet
  - `generatedMethodAnnotations` and `generatedClassAnnotations` - are not implemented yet
  - `compilerOptions` - not implemented yet
- some properties are required by the current implementation are not part of this proposal, because they exist only 
  due to some implementation issues, and could soon disappear or change significantly, for example:
  - `getScriptingClass` in the environment - is used for kotlin types instantiation; currently the instance of
    `JvmGetScriptingClass` should be always used
  - `baseClass` in the environment - should, in fact, be moved to the script definition properties  

The standard host functionality including discovery is implemented in the command-line compiler, gradle plugin and IDEA
plugin. But the following limitations are known:
  - if the script definition module is in the same project as the usage part, the module (and in particular - base 
    class) should be compiled to class files in order to be recognized by the discovery mechanism
  - the scripts in the new infrastructure can be only compiled directly by command-line compiler or by gradle plugin,
    so they will not work in the maven project or when imported into idea without delegating build tasks to gradle
  - the mechanisms for managing script definitions and resolving clashes are not implemented   
    
In general, the new scripting support is experimental and work in progress, so the stability of the interfaces and 
implementation is not guaranteed.         

### Examples

#### Script with maven dependencies resolving support
 
The complete source code could be found in the `libraries/samples/scripting/jvm-maven-deps` folder in the Kotlin source 
code repository. *(Note: discovery file is not part of the mentioned example projects in the Kotlin repository, due to
some clashes with the Kotlin build infrastructure.)*

Script base class:

```
@KotlinScript
@KotlinScriptFileExtension("scriptwithdeps.kts")
@KotlinScriptCompilationConfigurator(MyConfigurator::class)
@KotlinScriptEvaluator(BasicJvmScriptEvaluator::class)
abstract class MyScriptWithMavenDeps
```

Script static configuration properties list:

```
val myJvmConfigParams = jvmJavaHomeParams + // standard JVM parameters
    listOf(
        // default imports
        ScriptCompileConfigurationProperties.defaultImports to listOf(DependsOn::class.qualifiedName, Repository::class.qualifiedName),
        // dependencies to the libraries 
        ScriptCompileConfigurationProperties.dependencies to listOf(
            JvmDependency(
                scriptCompilationClasspathFromContext(
                    "scripting-jvm-maven-deps", // script library jar name
                    "kotlin-script-util" // DependsOn annotation is taken from script-util
                )
            )
        ),
        // request to call configurator on encountering these annotations
        ScriptCompileConfigurationProperties.refineConfigurationOnAnnotations to listOf(DependsOn::class, Repository::class)
    )
```

Compilation configurator

```
class MyConfigurator(val environment: ScriptingEnvironment) : ScriptCompilationConfigurator {

    private val resolver = FilesAndMavenResolver() // maven resolver provided by kotlin-script-util library

    override val defaultConfiguration = ScriptCompileConfiguration(environment, myJvmConfigParams)

    override suspend fun refineConfiguration(
        scriptSource: ScriptSource,
        configuration: ScriptCompileConfiguration,
        processedScriptData: ProcessedScriptData
    ): ResultWithDiagnostics<ScriptCompileConfiguration> {
        val annotations = processedScriptData.getOrNull(ProcessedScriptDataProperties.foundAnnotations)?.takeIf { it.isNotEmpty() }
                ?: return configuration.asSuccess()
        // ...
        // take annotations arguments and pass them to resolver
        // ...
        // wrap resulting references to resolved and downloaded jars to the returned refined configuration
        return ScriptCompileConfiguration(
            configuration, 
            ScriptCompileConfigurationProperties.dependencies to updatedDeps
        ).asSuccess()        
    }
}
```

The discovery file (resources):  
`META-INF/kotlin/script/templates/org.jetbrains.kotlin.script.examples.jvm.resolve.maven.MyScriptWithMavenDeps`

Simple host: eval function:

```
fun evalFile(scriptFile: File): ResultWithDiagnostics<EvaluationResult> {
    val scriptCompiler = JvmScriptCompiler(KJVMCompilerImpl(), DummyCompiledJvmScriptCache())
    val scriptDefinition = ScriptDefinitionFromAnnotatedBaseClass(
        ScriptingEnvironment(
            ScriptingEnvironmentProperties.baseClass<MyScriptWithMavenDeps>(),
            ScriptingEnvironmentProperties.getScriptingClass(JvmGetScriptingClass())
        )
    )

    val host = JvmBasicScriptingHost(
        scriptDefinition.compilationConfigurator,
        scriptCompiler,
        scriptDefinition.evaluator
    )

    return host.eval(scriptFile.toScriptSource(), ScriptCompileConfiguration(myJvmConfigParams), ScriptEvaluationEnvironment())
}
```

Example script (file: `hello.scriptwithdeps.kts`):

```
@file:DependsOn("junit:junit:4.11")

org.junit.Assert.assertTrue(true)

println("Hello, World!")

```
