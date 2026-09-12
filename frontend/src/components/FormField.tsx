import type { ReactNode } from 'react';

interface Props {
  id: string;
  label: string;
  error?: string;
  hint?: ReactNode;
  children: ReactNode;
}

/**
 * Label, control and error message wired together for screen readers.
 *
 * The error is linked via `aria-describedby` on the control itself (see the pages that
 * use this), so it is announced rather than only shown in red.
 */
export function FormField({ id, label, error, hint, children }: Props) {
  return (
    <div className="form-field">
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && !error && (
        <p className="field-hint" id={`${id}-hint`}>
          {hint}
        </p>
      )}
      {error && (
        <p className="field-error" id={`${id}-error`} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
