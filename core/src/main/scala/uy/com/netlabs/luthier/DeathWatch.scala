package uy.com.netlabs.luthier

/**
 * Classes that mixes this trait mark that they are disposable (whatever that means to them)
 * and when they are disposed, they notify whomever registers.
 */
trait Disposable {

  /**
   * Dispose this disposable, notifying registrated listeners afterwards.
   */
  def dispose() {
    disposeImpl()
    onDisposeActions foreach (_(this))
  }
  protected def disposeImpl(): Unit
  private[this] var onDisposeActions = Set.empty[this.type => Unit]
  /**
   * Register to be notified when this disposable is disposed.
   */
  def onDispose(action: this.type => Unit) = onDisposeActions += action
  /**
   * Unregisters a previous registration.
   */
  def unregisterOnDispose(action: this.type => Unit) = onDisposeActions -= action
}