export class HistorySelection {
  private routeKey: string | null = null;
  private selectedId: string | null = null;
  private generation = 0;

  observeRoute(key: string, id: string | null) {
    if (this.routeKey === key) return;
    this.routeKey = key;
    this.choose(id);
  }
  choose(id: string | null) {
    this.selectedId = id;
    this.generation++;
  }
  token() {
    return this.generation;
  }
  accepts(token: number) {
    return this.generation === token;
  }
  selects(id: string) {
    return this.selectedId === id;
  }
}
