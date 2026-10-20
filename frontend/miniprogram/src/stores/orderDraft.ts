import { ref } from 'vue';
import { session } from '../api/session';
// Only the ID crosses pages; recipient details never enter persistent storage.
export const selectedAddressId = ref<string | null>(null);
session.subscribe(() => {
  selectedAddressId.value = null;
});
