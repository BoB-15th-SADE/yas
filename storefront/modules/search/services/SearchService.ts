import apiClientService from '@/common/services/ApiClientService';
import { SearchParams } from '../models/SearchParams';
import { searchCatalog, suggestCatalog } from './CatalogSearch';

// This PoC uses product APIs without the optional Elasticsearch/Kafka stack.
export const getSuggestions = (keyword: string) => suggestCatalog(apiClientService, keyword);
export const searchProducts = (params: SearchParams) => searchCatalog(apiClientService, params);
