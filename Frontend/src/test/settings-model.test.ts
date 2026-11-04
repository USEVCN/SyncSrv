import { expect, it } from "vitest";
import { defaultTypography, validTypography } from "../modules/settings/settingsModel";
it("validates integral ordered typography without changing published defaults",()=>{
 expect(validTypography(defaultTypography)).toBe(true);
 expect(validTypography({...defaultTypography,body:14.5})).toBe(false);
 expect(validTypography({...defaultTypography,section:13})).toBe(false);
 expect(validTypography({...defaultTypography,caption:30})).toBe(false);
});
