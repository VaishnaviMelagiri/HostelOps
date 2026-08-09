/** @type {import('tailwindcss').Config} */
export default {
  // Tailwind scans these files for class names and emits CSS for only the ones it finds.
  // A class built by string concatenation (`"bg-" + colour`) will NOT be found - always write
  // whole class names, or Tailwind will silently omit the style.
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Bed status palette, defined once so the map, legend and badges cannot drift apart.
        // Phase 3 uses these; the proper design pass comes much later.
        status: {
          available: '#16a34a',
          pending:   '#d97706',
          allocated: '#2563eb',
          blocked:   '#dc2626',
        },
      },
    },
  },
  plugins: [],
};
