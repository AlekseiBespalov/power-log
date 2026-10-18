export class RetryBackoff {
  private delay = 1000;
  next(): number {
    const delay = this.delay;
    this.delay = Math.min(30000, delay * 2);
    return delay;
  }
  reset(): void {
    this.delay = 1000;
  }
}
