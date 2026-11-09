export interface BinaryPage {
  envelope: string;
  numbers: Record<string, Uint8Array>;
}

export interface Transport {
  call(op: string, request: string): Promise<string>;
  next(stream: string, request: string): Promise<string>;
  exportPage(request: string): Promise<BinaryPage>;
}

// Metro resolves './transport' to the .native or .web file; tsc reads this one.
export { platformTransport } from './transport.native';
