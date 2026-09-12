/** A user as returned by the API. There is deliberately no password field anywhere. */
export interface UserProfile {
  id: string;
  username: string;
  email: string;
  role: 'USER' | 'ADMIN';
  enabled: boolean;
  createdAt: string;
}

export interface RegistrationPayload {
  username: string;
  email: string;
  password: string;
}

export interface LoginPayload {
  identifier: string;
  password: string;
}

/** A field rejected by server-side validation, keyed by the form field it belongs to. */
export interface FieldViolation {
  field: string;
  message: string;
}
