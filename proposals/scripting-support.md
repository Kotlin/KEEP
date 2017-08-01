
# Kotlin Scripting support

*Replaces [Script Definition Template](https://github.com/Kotlin/KEEP/blob/master/proposals/script-definition-template.md)
proposal.*

## Feedback

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/75)

## Motivation

- Define Kotlin scripting and it's applications
- Describe intended use cases for the Kotlin scripting
- Define scripting support tha is: 
  - applicable to the all Kotlin platforms
  - provides sufficient control of interpretation and execution of scripts
  - simple enough to configure and customize
  - provides usable default components and configurations for the typical use cases    
- Provide basic examples of the scripting usage and implementation
- Address the issues found during the public usage of the current scripting support 

## Applications

* Build scripts (Gradle/Kobalt)
* Test scripts (Spek)
* Command-line utilities
* Routing script for ktor (get(“/hello”) {...} on top level)
* Type-safe configuration files (TeamCity)
* In-process scripting and REPL for IDE
* Consoles like IPython Notebook
* Game scripting engines
* ...

## Basic definitions

- **Script** - a text file written in Kotlin language but allowing top-level statements and having access to some 
  implicit (not directly mentioned in the script text) properties, as if the whole script body is a body of an implicit
  function
- **(Scripting) Host** - an application or a component which handles script execution   
- **REPL line** - a group of script text lines, executed in a single REPL eval call (*confusingly - a line == many text 
  lines, so maybe another term is needed.*)
- **Compiled script** - a binary compiled code if the script class, stored in memory or on disk, which could be loaded 
  and instantiated by appropriate platform
- **Script object** - instantiated script class, ready to be executed
- **External library** - a library whose declarations are available for the script being compiled, instantiated and 
  executed
- **External script** - another script whose declarations are available for the script being compiled, instantiated and 
  executed   
- **Execution environment** - the environment in which the script is instantiated and executed, defining which services,
  objects, actions, etc. are accessible for the script

## Use cases

### Embedded scripting

Embedded script applications are specialized consoles like Jupyter notebook, Spark shell, embedded games scripting, 
IDE and other application-level scripting, etc. 

#### Environment

The script is supposed to run in an execution environment, defined by the scripting host. The default set of the external 
libraries is defined by the scripting as well. But the script developer would need to be able to specify 
additional compilation and execution parameters, additional external libraries and scripts, e.g. using shebang 
notation and file-level annotations, or some other form that the host can understand and extract from the script. 
For example:
```
#! /path/to/kotlic -cp=/path/to/scriptDefinitionLib.jar -scriptDefinition=my.package.MyScriptDefinition -someCompilerOpt -script

// alternatively
@file:scriptDefinition("my.package.MyScriptDefinition")
@file:dependsOn("/path/to/scriptDefinitionLib.jar")
@file:compilerOptions("-someCompilerOpt")

// in addition (not covered by the sample shebang line)
@file:dependsOn("maven:artifact:1.0", "imported.package.*")
@file:require("path/to/externalScript.kts")
@file:runWith("my.package.MyScriptExecutor")
```

where
- *executor* is a class that instantiates and runs the compiled script, therefore defining the execution environment
- *script definition* is a class that configures script compilation (*see below*)

It would be nice to provide runners that support some typical mean for each platform to resolve external libraries from 
online repositories (e.g. - maven for JVM) out of the box.

#### Simple implementation

In the simple case, a developer wants to implement a scripting host to control script execution and provide the required 
environment. One may want to write something as simple as:
```
KotlinScripting.run(File("path/to/script.kts"))
```
or
```
KotlinScripting.eval("println(\"Hello from script!\")")
```
and the script should be executed in the desired environment. Since in this case no information on the script type, 
compilation and execution is provided, some defaults are taken. So if things need to be configured explicitly,
the code would look like:
```
val scriptingHost = KotlinScriptingHost(configurationParams...)
scriptingHost.run(File("path/to/script.kts"))
```
This also allows the developer to control the lifetime of the scripting host.

#### More control

To be able to run the script in the completely custom environment, the following things should be configured or 
provided on the host creation:
- *Executor* - the service that will actually run the compiled scripts in the required execution environment  
- *Compilation platform* - the service that will compile scripts into a form accepted by the Executor
- *Script definition* - an interface (or prototype) of the script class, expected by the executor, so the platform
  should compile script into the appropriate class
- *Preprocessor* - the service that can analyze the script and the environment before compilation, and provide platform
  with the additional info needed for compilation, e.g. used external libraries and properties that should be visible 
  from the script; in addition it may extract relevant part of the script for partial or staged script handling (e.g. as
  the dependencies/plugins sections in gradle).
  
#### Various script types

There could be a need to support various types of the scripts in the same environment. E.g. in a web framework there 
could be routing scripts and content scripts. For such cases one more component should be configured:
- *Selector* - the service that recognizes a script using file name or text

The same component could be used for script type selection in an IDE.

#### Caching

Since calling Kotlin compiler could be quite a heavy operation in comparison with typical script execution, the caching
of the compiled script should be supported by the compilation platform.

#### Execution lifecycle

The script is executed in the following pipeline:
- selection - the *Selectors* are called on the script to choose the set of services that will handle it
- preprocessing - the *Preprocessor* takes the script and the environment and extracts the compilation configuration
- compilation - the *Platform* takes the script, and the compilation configuration and provides the compiled class
- instantiation - the *Executor* takes the compiled class, the environment, and the compilation configuration and
  instantiates the script object, passing the required parameters from the environment to the constructor
- execution - the *Executor* takes the instantiated script and executes the appropriate method, passing arguments
  from the environment to it; this step could be repeated many times

#### Processing in an IDE

The script is handled in an IDE in the following sequence (*Note that the first two steps are the same as in the 
execution pipeline*):
- selection - the *Selectors* takes the script and selects the set of services that will handle it
- preprocessing - the *Preprocessor* takes the script and the environment and extracts the compilation configuration
- resolution info extraction - the *Platform* takes the script and the compilation configuration and provides the
  info required for rich editing the script

### Standalone scripting

Standalone scripting applications include command-line utilities and a standalone Kotlin REPL.

Standalone scripting is a variant of the embedded scripting with hosts provided with the Kotlin distribution.

#### Hosts

The standalone script could be executed e.g. using command line Kotlin compiler:

`kotlinc -script myscript.kts`

Or with some hypothetical dedicated runner, that we might want to include into the distribution:

`kotlinsh myscrtipt.ktsh`

To be able to use the Kotlin scripts in a Unix shell environment, the *shebang* (`#!`) syntax should be supported 
at the beginning of the script:
```
#! /path/to/kotlin/script/runner -some -params
```

#### Parameters

For the command line usage the support for script parameters is needed. The simplest form is to assume that the script
has access to the `args: Array<String>` property/parameter. More advanced is to support a declaration of the typed 
parameters, e.g.:

```
@file:param("name", String::class)
@file:param("num", Int::class)

// this script could be called with args "-name=abc -num=42" 
// and then in the body we can access parsed typed arguments

println("$name ${num/6}") 
```

#### IDE support

The IDE counterpart of the standalone scripting support should be able to extract execution environment properties from 
the script itself and some explicit configuration data and create appropriate virtual project/module environment that 
is able to resolve all symbols properly used in the script, including external libraries and scripts.

#### Standalone REPL

Standalone REPL is invoked by a dedicated host the same way as for standalone script but accepts user's input as script 
lines. It means that the declarations made on the previous lines are accessible on the subsequent ones.

### Project-level scripting

Applications: test definition scripts (Spek), ktor routing scripts, project-level REPL, build scripts, type safe 
config files, etc.
 
Project-level scripts are executed by some dedicated scripting host usually embedded into the project build system. 
From an IDE point of view, they are project-context dependent, but may not be part of the project sources. (In the same 
sense as e.g. gradle build scripts source is not considered as a part of the project sources.)

#### Discovery

The IDE needs to be able to extract scripts environment configurations from the project settings.
 
#### Project-level REPL
 
A REPL that has access to the project's compiled classes. 

## Proposal

### Architecture

The scripting support consists of the following components:
- **Platform** - interface for compilation and IDE support 
  - `(scriptSource, scriptDefinition, compilationConfig) -> compiledScript`
  - `(scriptSource, scriptDefinition, compilationConfig) -> ideDataForRichEditing`
  - predefined platforms based on the kotlin platforms: /JVM, /JS, /Native
  - custom/customized implementation possible
  - compiled scripts cashing should be implemented here
  - the `compilationConfig` is defined by the platform, and may affect code generation; typically contains:
    - additional dependencies
    - *required* scripts
    - expected parameters
  - should not keep the state of the script execution
- **Executor** - the component that receives compiled script instantiates it, and then executes it in a required 
  environment, supplying any arguments that the script requires:
  - `(compiledScript, compilationConfig, environment) -> scriptObject`
  - `(scriptObject, compilationConfig, environment) -> Unit`
  - uses `compilationConfig` and `environment` for "feeding" the script with the arguments
  - predefined platform-specific executors available, but usually provided by the scripting host
  - possible executors
    - JSR-223
    - IDEA REPL
    - Jupyter
    - Gradle
    - with specific coroutines context
    - ...
- **Preprocessor** - receives the script text before compilation and extracts the `compilationConfig`
  - `(scriptSource) -> compilationConfig`
  - *previously called DependenciesResolver*
  - have access to specific compiler services to:
    - extract script (file-level) annotations
    - extract parts of the script (*source sections plugin*)
    - (potentially) perform script lexing for advanced script analysis
  - predefined platform-specific preprocessors are available for standard cases, custom one could be provided by the 
    scripting host
- **Selector** - receives the script file and/or text before further analysis to determine whether the script belongs
  to this scripting host and the particular set of components
  - `(scriptSource) -> Boolean`
  - required for IDE support
  - could be combined with the preprocessor, since it may require the same text analysing services 
  - default and simple implementations are provided

### Script object

Script compiled into the following class

```
class Script(templateParams..., val implicitReceivers[], additionalArgs: Map<String, Any?>): ScriptDefinition(definitionClassParams...) {
    
    val val1: V1 by initOnce // for all vals/vars defined in the script body
    
    fun fn1(...): R1 {} // for all funcs defined in the script body
    
    class Cl1(...) {} // for all classes/objects defined in the script body
    
    var param1: P1 = additionalArgs["param1"] // for all additionalParams, the types are defined by `compilationConfig` extracted by Preprocessor 
    
    [suspend] fun <SAM name>(lambdaParams...): RetVal {
        with(implicitReceivers) {
            ...
        }
    }
}
```

In the REPL mode `implicitReceivers` will contain all previous script lines (the order is significant).

The `implicitReceivers` may also contain compiled external scripts objects.

*(The `with (implicitReceivers)` wrapping is needed in all methods and initializers defined in the class.)*

The additional parameters may include params extracted by preprocessor (`@file:param("name", String::class)`) or could 
be used for bindings mapping in JSR-223-like implementations ([KT-18917](https://youtrack.jetbrains.com/issue/KT-18917)).  

### Script definition

**Script Definition** is a configuration entity that combines platform, preprocessor, and selector in one entity 
for simplified discovery and configuration. 

The definition is a kotlin class declaration annotated with specific annotations. The class should be defined in the SAM 
notation, and the single abstract method will be used by the platform to compile the script body into.  
*(avoiding usage of the word "Template", since it seems confuses people)* :

```
@ScriptDefinition(
    platform = JVMScriptPlatform::class,
    preprocessor = MyScriptPreprocessor::class,
    selector = MyScriptSelector::class,
)
abstract class MyScript(project: Project, val name: String) {
    fun helper1() { ... } 
    
    [suspend] abstract fun samFn(params...): R // SAM notation
}
```

Alternatively, individual annotations for some elements are possible for specific cases:

```
@ScriptByFileNameSelector("*.gradle.kts")
```

### Compilation Platform

```
interface ScriptingPlatform<CompilationConfig> {
    fun compile(scriptSource, scriptBaseClass: KClass<Scritpt>, compilationConfig: CompilationConfig): KClass<out Script>
    fun <getIdeData>(scriptSource, scriptBaseClass: KClass<Script>, compilationConfig: CompilationConfig): <IdeData>
}
```

### Executor

```
interface ScriptExecutor<Script, CompilationConfig> {
    fun createInstance(compiledScript: KClass<out Script>, compilationConfig: CompilationConfig, params...): Script
    fun run(scriptObject: Script, compilationConfig: CompilationConfig): Unit
}
```

### Preprocessor

```
interface ScriptPreprocessor<CompilationConfig> {
    fun preprocess(scriptSource): CompilationConfig 
}
```

### Selector

```
interface ScriptSelector {
    fun isScript(scriptSource): boolean 
}
```


### Implementing Scripting Support
     
To implement (custom) scripting support one should do the following

1. Implement common (IDE and host) parts
   - Choose target platform or implement custom one
   - choose appropriate preprocessor or implement custom
   - choose/implement selector
   - define script class with the `@ScriptDefinition` annotation
   - implement executor for the script class

2. Implement the scripting host with the following functionality:
   - load script definition class
   - initialize the platform (e.g. setup java home, initial dependencies, etc)
   - initialize preprocessor with the platform (e.g. for using compilation services)
   - initialize executor
   - initialize selector
   - for each script:
     - (optionally) - check if the script is supported
     - call preprocessor
     - call platform to compile script
     - call execute to get an instance
     - call executor to run the script instance
   - *(the base host may implement most of the boilerplate)*
      
3. Implement IDE support host with the following functionality
   - load script definition class
   - initialize the platform (e.g. setup java home, initial dependencies, etc)
   - initialize preprocessor with the platform (e.g. for using compilation services)
   - initialize selector
   - provide these components to the IDE

### IDE Interface

In the IDE the support for rich editing could be implemented using the specific extension point:

```
interface CustomKotlinScriptingProvider {
    val scriptDefinition: kClass<*>
    val platform: ScriptingPlatform<CompilationConfig>
    val preprocessor: ScriptPreprocessor<CompilationConfig>
    val selector: ScriptSelector
}
```

### Standard hosts and discovery

For standard JVM compiler, a command line parameter could be used to specify a script definition class name, and regular 
compilation classpath could be used for dependencies.

Additionally, the automatic discovery of the templates marked by `@ScriptDefinition` annotation could be used with 
libraries that do not have plugins able to provide an extension. This could, for example, be used for test frameworks.

To find the script definition corresponding to the script file, the compiler uses one of the following methods:

* Use the script definition specified explicitly in CompilerConfiguration (the Gradle case);
* Scan for all .jar in the classpath, load the list of script template definitions in each .jar from the JAR metadata, 
  and detect the applicable one based on the `@ScriptFilePattern` annotation;
* Use an explicit annotation referring to the FQ name of the script definition class:

```
@file:ScriptTemplate("org.jetbrains.kotlin.gradle.GradleScript")
```

### Compilation Configuration

The `CompilationConfig` for the JVM platform may look like:
```
interface KotlinJVMScriptAdditionalParameters {
    val classpath: Iterable<File> // dependencies classpath
    val imports: Iterable<String> // implicit imports
    val sources: Iterable<File>   // dependencies sources for source navigation in IDE
    val scripts: Iterable<File>   // additional scripts to compile along with the current one
}
```
