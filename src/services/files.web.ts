export async function exportText(name: string, contents: string) {
  const url = URL.createObjectURL(new Blob([contents], { type: 'text/csv;charset=utf-8' }));
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = name; anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export async function importText(): Promise<{ name: string; text: string } | null> {
  return new Promise((resolve, reject) => {
    const input = document.createElement('input'); input.type = 'file'; input.accept = '.csv,text/csv';
    input.oncancel = () => resolve(null);
    input.onchange = async () => {
      const file = input.files?.[0]; if (!file) { resolve(null); return; }
      if (file.size > 25 * 1024 * 1024) { reject(new Error('Choose a CSV smaller than 25 MB.')); return; }
      try { resolve({ name: file.name, text: await file.text() }); } catch (error) { reject(error); }
    };
    input.click();
  });
}


export async function exportWorkoutFile(uri: string, name: string): Promise<void> {
  if (!uri.startsWith('blob:')) throw new Error('The browser ride export is unavailable.');
  const anchor = document.createElement('a'); anchor.href = uri; anchor.download = name; anchor.click();
  setTimeout(() => URL.revokeObjectURL(uri), 1000);
}
