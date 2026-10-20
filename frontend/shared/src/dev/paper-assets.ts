/** Original local fixtures; metadata is verified against dev-assets in a focused test. */
export const devPaperSamples = [
  {
    extension: 'DOCX',
    mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    bytes: 36840,
  },
  { extension: 'PDF', mime: 'application/pdf', bytes: 1780 },
  { extension: 'PNG', mime: 'image/png', bytes: 29062 },
  { extension: 'JPG', mime: 'image/jpeg', bytes: 36116 },
  { extension: 'WEBP', mime: 'image/webp', bytes: 18070 },
].map((file) => ({ ...file, path: `/dev-files/paper-sample.${file.extension.toLowerCase()}` }));
