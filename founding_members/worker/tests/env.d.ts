import type { Env } from "../src/types";

declare module "cloudflare:workers" {
  interface ProvidedEnv extends Env {}
}

declare namespace Cloudflare {
  interface GlobalProps {
    mainModule: typeof import("../src/index");
  }
}
