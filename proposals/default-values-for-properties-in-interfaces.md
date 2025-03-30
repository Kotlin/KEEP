# Default values for properties in interfaces

* **Type**: Design proposal
* **Author**: Marcin Moskała
* **Status**: Under consideration

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/pull/102).

## Proposition

Just as we can have default bodies for methods in interfaces, we should be able to give default values for properties:

```
interface MyTrait {
    
    var items: List<Int> = emptyList()
    
    fun isEmpty(): Boolean {
        return items.isEmpty()
    }
}
```

Under the hood it can work just as default bodies for methods - the compiler can use default property if the property is not overridden.

## Use cases

This would be useful besically everywhere we want to design in objective way. With that, more features can be implemented as a traits instead of as a classes. We can also implement multiple interfaces and extend only single class, so this gives us a lot of possibilities for using implementation to add some behavior.

Simple example is `BasePresenter`. Let's say we needs to collect jobs to cancel them when presenter is destroyed. We can implement it is an abstract class:

```
abstract class JobsHandlingPresenter : Presenter {

    var jobs: List<Job> = emptyList()

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
    }
}
```

Now let's say we need to add some other behavior using inheritance to some of presenters. Let's say we want to handle dialogs: (yes, it should be in Activity in MVP) 

```
abstract class DialogHandlingPresenter : Presenter {

    var dialogs: List<Dialog> = emptyList()

    override fun onDestroy() {
        dialogs.filter { it.isShowing }.forEach { it.cancel() }
    }
}
```

Although if we extend `JobsHandlingPresenter` then we cannot extend `BasePresenter`. The common outcome is that we end up with `BasePresenter` that has all this responsibilities:
```
abstract class JobsHandlingPresenter : Presenter {

    var jobs: List<Job> = emptyList()
    var dialogs: List<Dialog> = emptyList()

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        dialogs.filter { it.isShowing }.forEach { it.cancel() }
    }
}
```

Althoug this is not a good pattern because:
 * It does have much more then single responsibility
 * It provides responsibilities to classes they don't need

Simple solution would be to allow default values for properties in interfaces. Then we would define this responsibilities as separate interfaces and every presenter would use only traits it needs:
```
interface JobsHandlingPresenter : Presenter {

    var jobs: List<Job> = emptyList()
    var dialogs: List<Dialog> = emptyList()

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        dialogs.filter { it.isShowing }.forEach { it.cancel() }
    }
}

interface DialogHandlingPresenter : Presenter {

    var dialogs: List<Dialog> = emptyList()

    override fun onDestroy() {
        dialogs.filter { it.isShowing }.forEach { it.cancel() }
    }
}
```
