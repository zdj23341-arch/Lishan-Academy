import { createAdminApplication } from './bootstrap';
import 'element-plus/dist/index.css';
import './styles/admin.scss';
import App from './App.vue';
import { router } from './router';
createAdminApplication(App, router).mount('#app');
